package sruu.agents;

import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import sruu.ontology.EmergencyOntology;

/**
 * Agent Camion de Pompiers — FSM : IDLE → EN_ROUTE → ACTIVE → RETURNING
 * Capacités DF : FIRE + RESCUE
 * Condition ABORT : waterReserve <= 0
 * Lancement : jade.Boot fire1:sruu.agents.FireTruckAgent(15,15)
 */
public class FireTruckAgent extends Agent {

    private enum State { IDLE, EN_ROUTE, ACTIVE, RETURNING }
    private State state = State.IDLE;

    private int x, y;
    private int targetX, targetY;
    private String currentIncidentId;

    private int  waterReserve                 = 100;
    private static final int  WATER_PER_TICK  = 12;
    private static final int  SPEED           = 3;
    private static final long TICK_MS         = 1000;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length >= 2) {
            x = Integer.parseInt(args[0].toString());
            y = Integer.parseInt(args[1].toString());
        } else {
            x = (int)(Math.random() * 50);
            y = (int)(Math.random() * 50);
        }
        System.out.printf("[FireTruck:%s] Démarrage @ (%d,%d) eau=%d%n",
            getLocalName(), x, y, waterReserve);

        registerDF();

        // Déplacement + consommation eau
        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() { moveAndUpdate(); }
        });

        // Écouter CFP
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.CFP),
                    MessageTemplate.MatchOntology(EmergencyOntology.ONTOLOGY_NAME)
                );
                ACLMessage cfp = myAgent.receive(mt);
                if (cfp != null) handleCFP(cfp);
                else block();
            }
        });

        // Écouter ACCEPT_PROPOSAL
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) handleAcceptProposal(msg);
                else block();
            }
        });
    }

    // ── Contract Net ────────────────────────────────────────────────────────
    private void handleCFP(ACLMessage cfp) {
        ACLMessage reply = cfp.createReply();
        reply.setOntology(EmergencyOntology.ONTOLOGY_NAME);

        if (state != State.IDLE || waterReserve <= 0) {
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent(waterReserve <= 0 ? "NO_WATER" : "BUSY");
            send(reply);
            return;
        }

        String content = cfp.getContent();
        int incX  = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_X, 0);
        int incY  = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_Y, 0);
        double dist = Math.sqrt(Math.pow(incX - x, 2) + Math.pow(incY - y, 2));
        double eta  = dist / SPEED;

        reply.setPerformative(ACLMessage.PROPOSE);
        reply.setContent(EmergencyOntology.buildContent(
            EmergencyOntology.KEY_X,         x,
            EmergencyOntology.KEY_Y,         y,
            EmergencyOntology.KEY_STATUS,    state.name(),
            EmergencyOntology.KEY_UNIT_TYPE, "FireTruck",
            EmergencyOntology.KEY_DISTANCE,  String.format("%.1f", dist),
            EmergencyOntology.KEY_WORKLOAD,  0,
            EmergencyOntology.KEY_ETA,       String.format("%.1f", eta)
        ));
        send(reply);
    }

    private void handleAcceptProposal(ACLMessage accept) {
        String content    = accept.getContent();
        currentIncidentId = EmergencyOntology.getValue(content, EmergencyOntology.KEY_INCIDENT_ID);
        targetX = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_X, x);
        targetY = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_Y, y);
        state   = State.EN_ROUTE;
        System.out.printf("[FireTruck:%s] EN ROUTE → (%d,%d) [%s]%n",
            getLocalName(), targetX, targetY, currentIncidentId);
        notifyDispatcher(EmergencyOntology.STATUS_EN_ROUTE);
    }

    // ── Déplacement + eau ───────────────────────────────────────────────────
    private void moveAndUpdate() {
        if (state == State.IDLE) return;

        if (state == State.ACTIVE) {
            waterReserve -= WATER_PER_TICK;
            System.out.printf("[FireTruck:%s] Eau : %d%n", getLocalName(), waterReserve);
            if (waterReserve <= 0) { waterReserve = 0; triggerAbort(); return; }
        }

        int destX = (state == State.RETURNING) ? 0 : targetX;
        int destY = (state == State.RETURNING) ? 0 : targetY;
        int dx = destX - x, dy = destY - y;
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist <= SPEED) {
            x = destX; y = destY;
            onArrived();
        } else {
            double r = SPEED / dist;
            x += (int)(dx * r);
            y += (int)(dy * r);
        }
    }

    private void onArrived() {
        switch (state) {
            case EN_ROUTE:
                state = State.ACTIVE;
                System.out.printf("[FireTruck:%s] SUR_SCÈNE — extinction en cours%n", getLocalName());
                notifyDispatcher(EmergencyOntology.STATUS_ON_SCENE);
                addBehaviour(new WakerBehaviour(this, 10 * TICK_MS) {
                    @Override
                    protected void onWake() {
                        if (state == State.ACTIVE) {
                            state = State.RETURNING;
                            notifyDispatcher(EmergencyOntology.STATUS_RESOLVED);
                            System.out.printf("[FireTruck:%s] Incendie maîtrisé — retour%n", getLocalName());
                        }
                    }
                });
                break;
            case RETURNING:
                state        = State.IDLE;
                waterReserve = 100;
                System.out.printf("[FireTruck:%s] BASE — rechargé%n", getLocalName());
                notifyDispatcher(EmergencyOntology.STATUS_IDLE);
                break;
            default: break;
        }
    }

    private void triggerAbort() {
        state = State.RETURNING;
        System.out.printf("[FireTruck:%s] ABORT — eau épuisée !%n", getLocalName());
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("DispatcherService");
        template.addServices(sd);
        try {
            DFAgentDescription[] found = DFService.search(this, template);
            if (found.length > 0) {
                ACLMessage failure = new ACLMessage(ACLMessage.FAILURE);
                failure.setOntology(EmergencyOntology.ONTOLOGY_NAME);
                failure.addReceiver(found[0].getName());
                failure.setContent(EmergencyOntology.buildContent(
                    EmergencyOntology.KEY_INCIDENT_ID, currentIncidentId,
                    EmergencyOntology.KEY_STATUS,      EmergencyOntology.STATUS_ABORT,
                    "reason",                          "WATER_DEPLETED"
                ));
                send(failure);
            }
        } catch (FIPAException ignored) {}
    }

    private void notifyDispatcher(String status) {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("DispatcherService");
        template.addServices(sd);
        try {
            DFAgentDescription[] found = DFService.search(this, template);
            if (found.length > 0) {
                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.setOntology(EmergencyOntology.ONTOLOGY_NAME + "-STATUS");
                msg.addReceiver(found[0].getName());
                msg.setContent(EmergencyOntology.buildContent(
                    EmergencyOntology.KEY_INCIDENT_ID, currentIncidentId,
                    EmergencyOntology.KEY_STATUS,      status,
                    EmergencyOntology.KEY_X,           x,
                    EmergencyOntology.KEY_Y,           y
                ));
                send(msg);
            }
        } catch (FIPAException ignored) {}
    }

    private void registerDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd1 = new ServiceDescription();
        sd1.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        sd1.setName("FireTruck-" + getLocalName());
        sd1.addProperties(new Property("capability", EmergencyOntology.CAP_FIRE));
        ServiceDescription sd2 = new ServiceDescription();
        sd2.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        sd2.setName("FireTruck-RESCUE-" + getLocalName());
        sd2.addProperties(new Property("capability", EmergencyOntology.CAP_RESCUE));
        dfd.addServices(sd1);
        dfd.addServices(sd2);
        try {
            DFService.register(this, dfd);
            System.out.println("[FireTruck:" + getLocalName() + "] Enregistré (FIRE + RESCUE).");
        } catch (FIPAException e) {
            System.err.println("[FireTruck] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }
}