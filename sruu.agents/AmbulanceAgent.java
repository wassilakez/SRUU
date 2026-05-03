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
 * Agent Ambulance — FSM 4 états : IDLE → EN_ROUTE → ON_SCENE → RETURNING
 * Capacité DF : MEDICAL
 * Lancement : jade.Boot amb1:sruu.agents.AmbulanceAgent(5,10)
 */
public class AmbulanceAgent extends Agent {

    private enum State { IDLE, EN_ROUTE, ON_SCENE, RETURNING }
    private State state = State.IDLE;

    private int x, y;
    private int targetX, targetY;
    private String currentIncidentId;
    private int hospitalX = 0, hospitalY = 0;
    private String assignedHospital;

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
        System.out.printf("[Ambulance:%s] Démarrage @ (%d,%d)%n", getLocalName(), x, y);

        registerDF();

        // Déplacement incrémental
        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() { moveTowardsTarget(); }
        });

        // Écouter les CFP (Contract Net)
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

        // Écouter les ACCEPT_PROPOSAL
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

        if (state != State.IDLE) {
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent("NOT_AVAILABLE");
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
            EmergencyOntology.KEY_UNIT_TYPE, "Ambulance",
            EmergencyOntology.KEY_DISTANCE,  String.format("%.1f", dist),
            EmergencyOntology.KEY_WORKLOAD,  0,
            EmergencyOntology.KEY_ETA,       String.format("%.1f", eta)
        ));
        send(reply);
        System.out.printf("[Ambulance:%s] PROPOSE envoyé (dist=%.1f)%n", getLocalName(), dist);
    }

    private void handleAcceptProposal(ACLMessage accept) {
        String content    = accept.getContent();
        currentIncidentId = EmergencyOntology.getValue(content, EmergencyOntology.KEY_INCIDENT_ID);
        targetX = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_X, x);
        targetY = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_Y, y);
        state   = State.EN_ROUTE;

        System.out.printf("[Ambulance:%s] EN ROUTE → (%d,%d) [%s]%n",
            getLocalName(), targetX, targetY, currentIncidentId);

        notifyDispatcher(EmergencyOntology.STATUS_EN_ROUTE);
        requestHospital();
    }

    // ── Déplacement ─────────────────────────────────────────────────────────
    private void moveTowardsTarget() {
        if (state == State.IDLE) return;

        int destX = (state == State.RETURNING) ? hospitalX : targetX;
        int destY = (state == State.RETURNING) ? hospitalY : targetY;

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
                state = State.ON_SCENE;
                System.out.printf("[Ambulance:%s] SUR_SCÈNE — %s%n", getLocalName(), currentIncidentId);
                notifyDispatcher(EmergencyOntology.STATUS_ON_SCENE);
                // Intervention : 8 secondes
                addBehaviour(new WakerBehaviour(this, 8000) {
                    @Override
                    protected void onWake() {
                        state = State.RETURNING;
                        notifyDispatcher(EmergencyOntology.STATUS_RESOLVED);
                        System.out.printf("[Ambulance:%s] RETOUR vers hôpital%n", getLocalName());
                    }
                });
                break;
            case RETURNING:
                state = State.IDLE;
                System.out.printf("[Ambulance:%s] BASE — prêt%n", getLocalName());
                notifyDispatcher(EmergencyOntology.STATUS_IDLE);
                break;
            default: break;
        }
    }

    // ── Communication ────────────────────────────────────────────────────────
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

    private void requestHospital() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(EmergencyOntology.SERVICE_MEDICAL_COORDINATOR);
        template.addServices(sd);
        try {
            DFAgentDescription[] found = DFService.search(this, template);
            if (found.length > 0) {
                ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
                req.setOntology(EmergencyOntology.ONTOLOGY_NAME);
                req.addReceiver(found[0].getName());
                req.setContent(EmergencyOntology.buildContent(
                    EmergencyOntology.KEY_X, x,
                    EmergencyOntology.KEY_Y, y
                ));
                send(req);

                // Réceptionner la réponse de manière non-bloquante
                addBehaviour(new SimpleBehaviour(this) {
                    boolean done = false;
                    @Override
                    public void action() {
                        MessageTemplate mt = MessageTemplate.and(
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.MatchOntology(EmergencyOntology.ONTOLOGY_NAME)
                        );
                        ACLMessage resp = myAgent.receive(mt);
                        if (resp != null) {
                            hospitalX        = EmergencyOntology.getIntValue(resp.getContent(), EmergencyOntology.KEY_X, 0);
                            hospitalY        = EmergencyOntology.getIntValue(resp.getContent(), EmergencyOntology.KEY_Y, 0);
                            assignedHospital = EmergencyOntology.getValue(resp.getContent(), EmergencyOntology.KEY_HOSPITAL);
                            System.out.printf("[Ambulance:%s] Hôpital : %s%n", myAgent.getLocalName(), assignedHospital);
                            done = true;
                        } else { block(); }
                    }
                    @Override public boolean done() { return done; }
                });
            }
        } catch (FIPAException ignored) {}
    }

    // ── DF ───────────────────────────────────────────────────────────────────
    private void registerDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(EmergencyOntology.SERVICE_EMERGENCY_UNIT);
        sd.setName("Ambulance-" + getLocalName());
        sd.addProperties(new Property("capability", EmergencyOntology.CAP_MEDICAL));
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("[Ambulance:" + getLocalName() + "] Enregistré (MEDICAL).");
        } catch (FIPAException e) {
            System.err.println("[Ambulance] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }
}
