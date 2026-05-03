package sruu.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import sruu.ontology.EmergencyOntology;

/**
 * Agent Camion de Pompiers — Unité FIRE + RESCUE.
 *
 * LIEN COURS §3.1-C : Contract Net — PROPOSE au Dispatcher, ABORT si eau épuisée.
 * LIEN COURS §3.2 AGR : deux rôles DF : CAP_FIRE et CAP_RESCUE.
 * LIEN COURS §3.3 BDI : "si eau épuisée → ABORT" = décision rationnelle locale.
 *
 * FSM :
 *   IDLE → EN_ROUTE → ACTIVE (extinction/secours) → RETURNING → IDLE
 *
 * Condition d'abandon obligatoire :
 *   Réserve d'eau initialisée à WATER_MAX. Consommée chaque tick sur les lieux.
 *   Si réserve = 0 → INFORM ABORT au Dispatcher → réaffectation dynamique.
 *
 * Arguments JADE : position initiale "(x,y)"
 */
public class FireTruckAgent extends Agent {

    private enum State { IDLE, EN_ROUTE, ACTIVE, RETURNING }

    // Réserve d'eau (unités)
    private static final int    WATER_MAX        = 10;
    private static final long   TICK_MS          = 1_000;
    private static final long   WORK_TICK_MS     = 2_000; // tick d'extinction
    private static final double SPEED            = 1.2;

    private State  state             = State.IDLE;
    private double posX, posY;
    private double targetX, targetY;
    private String currentIncidentId = null;
    private String currentIncidentType = null;
    private int    waterLevel        = WATER_MAX;
    private AID    dispatcherAID     = null;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length == 1) {
            String[] coords = args[0].toString()
                                     .replaceAll("[^0-9,]", "").split(",");
            if (coords.length == 2) {
                posX = Double.parseDouble(coords[0]);
                posY = Double.parseDouble(coords[1]);
            }
        }
        System.out.printf("[FIRETRUCK:%s] Démarrage @ (%.0f,%.0f) — eau=%d%n",
                getLocalName(), posX, posY, waterLevel);

        registerToDF();

        // ── Comportement 1 : écouter CFP ─────────────────────────────────
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive(
                    MessageTemplate.MatchPerformative(ACLMessage.CFP));
                if (msg != null) {
                    handleCFP(msg);
                } else {
                    block();
                }
            }
        });

        // ── Comportement 2 : écouter ACCEPT / REJECT ─────────────────────
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                    MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL));
                ACLMessage msg = receive(mt);
                if (msg != null && msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                    handleAccept(msg);
                } else if (msg != null) {
                    // REJECT : rester IDLE
                    System.out.printf("[FIRETRUCK:%s] Proposition rejetée.%n",
                            getLocalName());
                } else {
                    block();
                }
            }
        });

        // ── Comportement 3 : déplacement ─────────────────────────────────
        addBehaviour(new TickBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() {
                if (state == State.EN_ROUTE || state == State.RETURNING) {
                    moveTowards(targetX, targetY);
                }
            }
        });

        System.out.printf("[FIRETRUCK:%s] Prêt — IDLE%n", getLocalName());
    }

    // ════════════════════════════════════════════════════════════════════════
    // CFP
    // ════════════════════════════════════════════════════════════════════════
    private void handleCFP(ACLMessage cfp) {
        if (state != State.IDLE) {
            ACLMessage refuse = cfp.createReply();
            refuse.setPerformative(ACLMessage.REFUSE);
            refuse.setContent("BUSY");
            send(refuse);
            return;
        }

        String content = cfp.getContent();
        String incType = EmergencyOntology.get(content, EmergencyOntology.KEY_INCIDENT_TYPE);

        // Bonus de type : expert FIRE et STRUCTURAL_COLLAPSE
        double typeBonus;
        switch (incType) {
            case EmergencyOntology.INCIDENT_FIRE:
                typeBonus = 1.0; break;
            case EmergencyOntology.INCIDENT_STRUCTURAL_COLLAPSE:
                typeBonus = 0.9; break;
            default:
                typeBonus = 0.2; break;
        }

        double incX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        double incY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));
        double dist = distance(posX, posY, incX, incY);
        double eta  = dist / SPEED;

        System.out.printf("[FIRETRUCK:%s] CFP pour %s type=%s dist=%.1f%n",
                getLocalName(), cfp.getConversationId(), incType, dist);

        ACLMessage propose = cfp.createReply();
        propose.setPerformative(ACLMessage.PROPOSE);
        propose.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_UNIT_TYPE,   "FIRETRUCK",
            EmergencyOntology.KEY_UNIT_STATUS, EmergencyOntology.STATUS_IDLE,
            EmergencyOntology.KEY_COORD_X,     String.valueOf((int) posX),
            EmergencyOntology.KEY_COORD_Y,     String.valueOf((int) posY),
            EmergencyOntology.KEY_ETA,         String.valueOf((int) eta)
        ));
        send(propose);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ACCEPTATION
    // ════════════════════════════════════════════════════════════════════════
    private void handleAccept(ACLMessage accept) {
        String content = accept.getContent();
        currentIncidentId   = accept.getConversationId();
        currentIncidentType = EmergencyOntology.get(content,
                                EmergencyOntology.KEY_INCIDENT_TYPE);
        targetX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        targetY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));

        state = State.EN_ROUTE;
        System.out.printf("[FIRETRUCK:%s] ACCEPTED → EN_ROUTE (%.0f,%.0f) [%s]%n",
                getLocalName(), targetX, targetY, currentIncidentId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // DÉPLACEMENT
    // ════════════════════════════════════════════════════════════════════════
    private void moveTowards(double tx, double ty) {
        double dx = tx - posX;
        double dy = ty - posY;
        double d  = Math.sqrt(dx * dx + dy * dy);

        if (d <= SPEED) {
            posX = tx; posY = ty;
            onArrived();
        } else {
            posX += SPEED * dx / d;
            posY += SPEED * dy / d;
        }
    }

    private void onArrived() {
        if (state == State.EN_ROUTE) {
            state = State.ACTIVE;
            System.out.printf("[FIRETRUCK:%s] ARRIVÉ sur %s → ACTIVE (eau=%d)%n",
                    getLocalName(), currentIncidentId, waterLevel);

            notifyDispatcher(EmergencyOntology.PERF_ARRIVED, currentIncidentId);
            startWorkCycle();

        } else if (state == State.RETURNING) {
            // Rechargement complet à la base
            waterLevel = WATER_MAX;
            state = State.IDLE;
            System.out.printf("[FIRETRUCK:%s] Retour base — recharge complète — IDLE%n",
                    getLocalName());
        }
    }

    /**
     * Cycle de travail sur les lieux.
     * LIEN §3.3 BDI : chaque tick consomme de l'eau.
     * Si eau = 0 → condition d'abandon → ABORT.
     */
    private void startWorkCycle() {
        addBehaviour(new TickBehaviour(this, WORK_TICK_MS) {
            @Override
            protected void onTick() {
                if (state != State.ACTIVE) { stop(); return; }

                waterLevel--;
                System.out.printf("[FIRETRUCK:%s] Extinction en cours [%s] eau=%d%n",
                        getLocalName(), currentIncidentId, waterLevel);

                if (waterLevel <= 0) {
                    // ── CONDITION D'ABANDON : eau épuisée ──────────────────
                    // LIEN §projet : "forçant l'envoi d'un INFORM : ABORT"
                    System.out.printf(
                        "[FIRETRUCK:%s] EAU ÉPUISÉE — ABORT [%s]%n",
                        getLocalName(), currentIncidentId);

                    notifyDispatcher(
                        EmergencyOntology.PERF_ABORT + ";reason=WATER_EXHAUSTED",
                        currentIncidentId);

                    currentIncidentId = null;
                    state = State.RETURNING;
                    // Retour à la base (position actuelle = on attend le tick)
                    targetX = posX;
                    targetY = posY;
                    waterLevel = WATER_MAX; // rechargement symbolique en route
                    stop();

                } else if (waterLevel == WATER_MAX / 2) {
                    // À mi-réserve : incident probablement résolu (simulation)
                    System.out.printf("[FIRETRUCK:%s] Incident %s RÉSOLU%n",
                            getLocalName(), currentIncidentId);
                    notifyDispatcher(
                        EmergencyOntology.PERF_RESOLVED, currentIncidentId);
                    currentIncidentId = null;
                    state = State.RETURNING;
                    stop();
                }
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ════════════════════════════════════════════════════════════════════════
    private void notifyDispatcher(String event, String incidentId) {
        AID dest = findDispatcher();
        if (dest == null) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(dest);
        msg.setConversationId(incidentId);
        msg.setContent(event + ";unit=" + getLocalName());
        send(msg);
    }

    private AID findDispatcher() {
        if (dispatcherAID != null) return dispatcherAID;
        try {
            DFAgentDescription t = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.SERVICE_DISPATCHER);
            t.addServices(sd);
            DFAgentDescription[] r = DFService.search(this, t);
            if (r.length > 0) { dispatcherAID = r[0].getName(); return dispatcherAID; }
        } catch (Exception ignored) {}
        return null;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    private void registerToDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            // Double rôle AGR : CAP_FIRE et CAP_RESCUE
            ServiceDescription sd1 = new ServiceDescription();
            sd1.setType(EmergencyOntology.CAP_FIRE);
            sd1.setName("FireUnit");
            ServiceDescription sd2 = new ServiceDescription();
            sd2.setType(EmergencyOntology.CAP_RESCUE);
            sd2.setName("RescueUnit");
            dfd.addServices(sd1);
            dfd.addServices(sd2);
            DFService.register(this, dfd);
            System.out.printf("[FIRETRUCK:%s] Enregistré DF (rôles=%s,%s)%n",
                    getLocalName(), EmergencyOntology.CAP_FIRE,
                    EmergencyOntology.CAP_RESCUE);
        } catch (Exception e) {
            System.err.println("[FIRETRUCK:" + getLocalName()
                    + "] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[FIRETRUCK:" + getLocalName() + "] Arrêté.");
    }
}