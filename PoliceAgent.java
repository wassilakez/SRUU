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
 * Agent Police — FSM : PATROLLING → EN_ROUTE → SECURING → RETURNING → PATROLLING
 * Capacités DF : CROWD_CONTROL + PERIMETER
 * Lancement : jade.Boot police1:sruu.agents.PoliceAgent(20,15)
 */
public class PoliceAgent extends Agent {

    private enum State { PATROLLING, EN_ROUTE, SECURING, RETURNING }
    private State state = State.PATROLLING;

    private int x, y;
    private int targetX, targetY;
    private String currentIncidentId;

    private int[][] patrolWaypoints;
    private int patrolIndex = 0;

    private static final int  SPEED            = 2;
    private static final long TICK_MS          = 1000;
    private static final int  PERIMETER_RADIUS = 5;

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

        // Circuit de patrouille autour de la position initiale
        patrolWaypoints = new int[][]{
            {x + 5, y},
            {x + 5, y + 5},
            {x,     y + 5},
            {x,     y}
        };
        targetX = patrolWaypoints[0][0];
        targetY = patrolWaypoints[0][1];

        System.out.printf("[Police:%s] Démarrage @ (%d,%d) — PATROUILLE%n", getLocalName(), x, y);

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
        int dx = targetX - x, dy = targetY - y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist <= SPEED) {
            x = targetX; y = targetY;
            onArrived();
        } else {
            double r = SPEED / dist;
            x += (int)(dx * r);
            y += (int)(dy * r);
        }
    }

    private void onArrived() {
        switch (state) {
            case PATROLLING:
                patrolIndex = (patrolIndex + 1) % patrolWaypoints.length;
                targetX = patrolWaypoints[patrolIndex][0];
                targetY = patrolWaypoints[patrolIndex][1];
                break;
            case EN_ROUTE:
                state = State.SECURING;
                System.out.printf("[Police:%s] SÉCURISATION périmètre rayon=%d%n",
                    getLocalName(), PERIMETER_RADIUS);
                notifyDispatcher(EmergencyOntology.STATUS_ON_SCENE);
                broadcastPerimeterSecured();
                addBehaviour(new WakerBehaviour(this, 15_000) {
                    @Override
                    protected void onWake() {
                        if (state == State.SECURING) {
                            state   = State.RETURNING;
                            targetX = patrolWaypoints[0][0];
                            targetY = patrolWaypoints[0][1];
                            notifyDispatcher(EmergencyOntology.STATUS_RESOLVED);
                        }
                    }
                });
                break;
            case RETURNING:
                state = State.PATROLLING;
                patrolIndex = 0;
                targetX = patrolWaypoints[0][0];
                targetY = patrolWaypoints[0][1];
                System.out.printf("[Police:%s] RETOUR PATROUILLE%n", getLocalName());
                notifyDispatcher(EmergencyOntology.STATUS_PATROLLING);
                break;
            default: break;
        }
    }

    private void broadcastPerimeterSecured() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        template.addServices(sd);
        try {
            DFAgentDescription[] units = DFService.search(this, template);
            if (units.length > 0) {
                ACLMessage bc = new ACLMessage(ACLMessage.INFORM);
                bc.setOntology(EmergencyOntology.ONTOLOGY_NAME);
                for (DFAgentDescription u : units) bc.addReceiver(u.getName());
                bc.setContent(EmergencyOntology.buildContent(
                    EmergencyOntology.KEY_MESSAGE,     "PERIMETER_SECURED",
                    EmergencyOntology.KEY_INCIDENT_ID, currentIncidentId,
                    EmergencyOntology.KEY_X,           x,
                    EmergencyOntology.KEY_Y,           y,
                    "radius",                          PERIMETER_RADIUS
                ));
                send(bc);
            }
        } catch (FIPAException ignored) {}
    }

    // ── Contract Net ────────────────────────────────────────────────────────
    private void handleCFP(ACLMessage cfp) {
        ACLMessage reply = cfp.createReply();
        reply.setOntology(EmergencyOntology.ONTOLOGY_NAME);

        if (state != State.PATROLLING) {
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent("BUSY");
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
            EmergencyOntology.KEY_UNIT_TYPE, "Police",
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
        System.out.printf("[Police:%s] DÉPÊCHÉ → (%d,%d) [%s]%n",
            getLocalName(), targetX, targetY, currentIncidentId);
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
        ServiceDescription sd1 = new ServiceDescription();
        sd1.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        sd1.setName("Police-CC-" + getLocalName());
        sd1.addProperties(new Property("capability", EmergencyOntology.CAP_CROWD_CONTROL));
        ServiceDescription sd2 = new ServiceDescription();
        sd2.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        sd2.setName("Police-P-" + getLocalName());
        sd2.addProperties(new Property("capability", EmergencyOntology.CAP_PERIMETER));
        dfd.addServices(sd1);
        dfd.addServices(sd2);
        try {
            DFService.register(this, dfd);
            System.out.println("[Police:" + getLocalName() + "] Enregistré (CROWD_CONTROL + PERIMETER).");
        } catch (FIPAException e) {
            System.err.println("[Police] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }
}