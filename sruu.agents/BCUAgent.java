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
 * Agent BCU (Biohazard Containment Unit)
 * FSM : STANDBY → EN_ROUTE → CONTAINMENT → DECONTAMINATION → RETURNING → STANDBY
 * Capacité DF : BIOHAZARD_CONTAINMENT
 * Gère : BIOHAZARD + CRYOGENIC_LEAK
 * Lancement : jade.Boot bcu1:sruu.agents.BCUAgent(35,40)
 */
public class BCUAgent extends Agent {

    private enum State { STANDBY, EN_ROUTE, CONTAINMENT, DECONTAMINATION, RETURNING }
    private State state = State.STANDBY;

    private int x, y;
    private int targetX, targetY;
    private String currentIncidentId;
    private String currentIncidentType;

    private static final int  SPEED   = 2;
    private static final long TICK_MS = 1000;

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
        System.out.printf("[BCU:%s] Démarrage @ (%d,%d) — STANDBY%n", getLocalName(), x, y);

        registerDF();

        // Déplacement
        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() { move(); }
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

    // ── Déplacement ─────────────────────────────────────────────────────────
    private void move() {
        if (state == State.STANDBY || state == State.CONTAINMENT || state == State.DECONTAMINATION)
            return;

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
                state = State.CONTAINMENT;
                System.out.printf("[BCU:%s] CONFINEMENT — %s @ (%d,%d)%n",
                    getLocalName(), currentIncidentType, x, y);
                notifyDispatcher(EmergencyOntology.STATUS_ON_SCENE);
                broadcastHazardZone("HAZARD_ZONE_ACTIVE");

                long duration = EmergencyOntology.INCIDENT_CRYOGENIC_LEAK
                    .equals(currentIncidentType) ? 8_000 : 12_000;
                addBehaviour(new WakerBehaviour(this, duration) {
                    @Override
                    protected void onWake() {
                        if (state == State.CONTAINMENT) startDecontamination();
                    }
                });
                break;
            case RETURNING:
                state = State.STANDBY;
                System.out.printf("[BCU:%s] BASE — STANDBY%n", getLocalName());
                notifyDispatcher(EmergencyOntology.STATUS_IDLE);
                break;
            default: break;
        }
    }

    private void startDecontamination() {
        state = State.DECONTAMINATION;
        System.out.printf("[BCU:%s] DÉCONTAMINATION — 5s%n", getLocalName());
        broadcastHazardZone("DECONTAMINATION_IN_PROGRESS");
        addBehaviour(new WakerBehaviour(this, 5_000) {
            @Override
            protected void onWake() {
                state   = State.RETURNING;
                targetX = 0; targetY = 0;
                notifyDispatcher(EmergencyOntology.STATUS_RESOLVED);
                System.out.printf("[BCU:%s] Zone sécurisée — retour base%n", getLocalName());
            }
        });
    }

    private void broadcastHazardZone(String type) {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        template.addServices(sd);
        try {
            DFAgentDescription[] units = DFService.search(this, template);
            if (units.length > 0) {
                ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
                alert.setOntology(EmergencyOntology.ONTOLOGY_NAME);
                for (DFAgentDescription u : units) alert.addReceiver(u.getName());
                alert.setContent(EmergencyOntology.buildContent(
                    EmergencyOntology.KEY_MESSAGE,     type,
                    EmergencyOntology.KEY_INCIDENT_ID, currentIncidentId,
                    EmergencyOntology.KEY_X,           x,
                    EmergencyOntology.KEY_Y,           y
                ));
                send(alert);
            }
        } catch (FIPAException ignored) {}
    }

    // ── Contract Net ────────────────────────────────────────────────────────
    private void handleCFP(ACLMessage cfp) {
        ACLMessage reply = cfp.createReply();
        reply.setOntology(EmergencyOntology.ONTOLOGY_NAME);

        String content = cfp.getContent();
        String incType = EmergencyOntology.getValue(content, EmergencyOntology.KEY_INCIDENT_TYPE);

        boolean relevant = EmergencyOntology.INCIDENT_BIOHAZARD.equals(incType)
                        || EmergencyOntology.INCIDENT_CRYOGENIC_LEAK.equals(incType);

        if (!relevant || state != State.STANDBY) {
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent(relevant ? "BUSY" : "TYPE_MISMATCH");
            send(reply);
            return;
        }

        int incX  = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_X, 0);
        int incY  = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_Y, 0);
        double dist = Math.sqrt(Math.pow(incX - x, 2) + Math.pow(incY - y, 2));
        double eta  = dist / SPEED;

        reply.setPerformative(ACLMessage.PROPOSE);
        reply.setContent(EmergencyOntology.buildContent(
            EmergencyOntology.KEY_X,         x,
            EmergencyOntology.KEY_Y,         y,
            EmergencyOntology.KEY_STATUS,    state.name(),
            EmergencyOntology.KEY_UNIT_TYPE, "BCU",
            EmergencyOntology.KEY_DISTANCE,  String.format("%.1f", dist),
            EmergencyOntology.KEY_WORKLOAD,  0,
            EmergencyOntology.KEY_ETA,       String.format("%.1f", eta)
        ));
        send(reply);
        System.out.printf("[BCU:%s] PROPOSE pour %s (dist=%.1f)%n", getLocalName(), incType, dist);
    }

    private void handleAcceptProposal(ACLMessage accept) {
        String content       = accept.getContent();
        currentIncidentId    = EmergencyOntology.getValue(content, EmergencyOntology.KEY_INCIDENT_ID);
        currentIncidentType  = EmergencyOntology.getValue(content, EmergencyOntology.KEY_INCIDENT_TYPE);
        targetX = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_X, x);
        targetY = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_Y, y);
        state   = State.EN_ROUTE;
        System.out.printf("[BCU:%s] EN ROUTE → (%d,%d) [%s]%n",
            getLocalName(), targetX, targetY, currentIncidentType);
        notifyDispatcher(EmergencyOntology.STATUS_EN_ROUTE);
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
        ServiceDescription sd = new ServiceDescription();
        sd.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        sd.setName("BCU-" + getLocalName());
        sd.addProperties(new Property("capability", EmergencyOntology.CAP_BIOHAZARD_CONTAINMENT));
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("[BCU:" + getLocalName() + "] Enregistré (BIOHAZARD_CONTAINMENT).");
        } catch (FIPAException e) {
            System.err.println("[BCU] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }
}
