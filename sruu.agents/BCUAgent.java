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
 * Agent BCU — Biohazard Containment Unit.
 *
 * LIEN COURS §3.1-C : Contract Net — seul agent capable de traiter BIOHAZARD
 *   et CRYOGENIC_LEAK, donc toujours sélectionné si disponible.
 * LIEN COURS §3.2 AGR : rôle DF = CAP_BIOHAZARD_CONTAINMENT.
 * LIEN COURS §3.3 BDI : décision locale — niveau de décontamination restant.
 *   Si réserve décontaminant épuisée → ABORT (même logique que le FireTruck).
 *
 * FSM :
 *   IDLE → EN_ROUTE → DECONTAMINATING → RETURNING → IDLE
 *
 * Particularité :
 *   - Durée de décontamination plus longue (incidents plus dangereux).
 *   - Condition ABORT : réserve de décontaminant épuisée (DECONTAMINANT_MAX ticks).
 *
 * Arguments JADE : position initiale "(x,y)"
 */
public class BCUAgent extends Agent {

    private enum State { IDLE, EN_ROUTE, DECONTAMINATING, RETURNING }

    private static final int    DECONTAMINANT_MAX  = 8;    // réserve initiale
    private static final double SPEED              = 0.8;  // plus lent (équipement lourd)
    private static final long   TICK_MS            = 1_000;
    private static final long   DECON_TICK_MS      = 2_500; // tick de décontamination
    // À mi-réserve on considère l'incident résolu (durée de décontamination)
    private static final int    RESOLVED_AT_LEVEL  = DECONTAMINANT_MAX / 2;

    private State  state                = State.IDLE;
    private double posX, posY;
    private double targetX, targetY;
    private String currentIncidentId   = null;
    private int    decontaminantLevel  = DECONTAMINANT_MAX;
    private AID    dispatcherAID       = null;

    @Override
    protected void setup() {
    	Object[] args = getArguments();
    	if (args != null && args.length >= 2) {
    	    try {
    	        posX = Double.parseDouble(args[0].toString());
    	        posY = Double.parseDouble(args[1].toString());
    	    } catch (NumberFormatException e) {}
    	}
        System.out.printf("[BCU:%s] Démarrage @ (%.0f,%.0f) — décontaminant=%d%n",
                getLocalName(), posX, posY, decontaminantLevel);

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
                    System.out.printf("[BCU:%s] Proposition rejetée.%n", getLocalName());
                } else {
                    block();
                }
            }
        });

        // ── Comportement 3 : déplacement ─────────────────────────────────
        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() {
                if (state == State.EN_ROUTE || state == State.RETURNING) {
                    moveTowards(targetX, targetY);
                }
            }
        });

        System.out.printf("[BCU:%s] Prêt — IDLE%n", getLocalName());
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

        // BCU : expert absolu sur BIOHAZARD et CRYOGENIC_LEAK
        double typeBonus;
        switch (incType) {
            case EmergencyOntology.INCIDENT_BIOHAZARD:
            case EmergencyOntology.INCIDENT_CRYOGENIC_LEAK:
                typeBonus = 1.0; break;
            default:
                // BCU n'intervient que sur les incidents chimiques/biologiques
                // → REFUSE poliment pour les autres types
                ACLMessage refuse = cfp.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                refuse.setContent("NOT_SPECIALIZED");
                send(refuse);
                System.out.printf("[BCU:%s] REFUSE — type %s non spécialisé%n",
                        getLocalName(), incType);
                return;
        }

        double incX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        double incY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));
        double dist = distance(posX, posY, incX, incY);
        double eta  = dist / SPEED;

        System.out.printf("[BCU:%s] CFP pour %s type=%s dist=%.1f%n",
                getLocalName(), cfp.getConversationId(), incType, dist);

        ACLMessage propose = cfp.createReply();
        propose.setPerformative(ACLMessage.PROPOSE);
        propose.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_UNIT_TYPE,   "BCU",
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
    	if (state != State.IDLE) {
            // Refuser poliment
            ACLMessage refuse = accept.createReply();
            refuse.setPerformative(ACLMessage.FAILURE);
            refuse.setContent("BUSY");
            send(refuse);
            System.out.printf("[BCU:%s] REFUSE (déjà occupé)%n", getLocalName());
            return;
        }
        String content = accept.getContent();
        currentIncidentId = accept.getConversationId();
        targetX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        targetY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));

        state = State.EN_ROUTE;
        System.out.printf("[BCU:%s] ACCEPTED → EN_ROUTE (%.0f,%.0f) [%s]%n",
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
            state = State.DECONTAMINATING;
            System.out.printf("[BCU:%s] ARRIVÉ — décontamination débutée [%s] décontaminant=%d%n",
                    getLocalName(), currentIncidentId, decontaminantLevel);

            notifyDispatcher(EmergencyOntology.PERF_ARRIVED, currentIncidentId);
            startDecontaminationCycle();

        } else if (state == State.RETURNING) {
            // Rechargement complet
            decontaminantLevel = DECONTAMINANT_MAX;
            state = State.IDLE;
            System.out.printf("[BCU:%s] Retour base — rechargé — IDLE%n",
                    getLocalName());
        }
    }

    /**
     * Cycle de décontamination.
     * LIEN §3.3 BDI : si réserve = 0 → ABORT (même pattern que FireTruck).
     * À mi-réserve → incident résolu (simulation durée de décontamination).
     */
    private void startDecontaminationCycle() {
        addBehaviour(new TickerBehaviour(this, DECON_TICK_MS) {
            @Override
            protected void onTick() {
                if (state != State.DECONTAMINATING) { stop(); return; }

                decontaminantLevel--;
                System.out.printf("[BCU:%s] Décontamination [%s] restant=%d%n",
                        getLocalName(), currentIncidentId, decontaminantLevel);

                if (decontaminantLevel <= 0) {
                    // Réserve épuisée — ABORT
                    System.out.printf("[BCU:%s] DÉCONTAMINANT ÉPUISÉ — ABORT [%s]%n",
                            getLocalName(), currentIncidentId);

                    notifyDispatcher(
                        EmergencyOntology.PERF_ABORT + ";reason=DECONTAMINANT_EXHAUSTED",
                        currentIncidentId);

                    currentIncidentId  = null;
                    state              = State.RETURNING;
                    decontaminantLevel = DECONTAMINANT_MAX; // rechargement en route
                    targetX = posX;
                    targetY = posY;
                    stop();

                } else if (decontaminantLevel == RESOLVED_AT_LEVEL) {
                    // Incident contenu — résolu
                    System.out.printf("[BCU:%s] Zone DÉCONTAMINÉE → RESOLVED [%s]%n",
                            getLocalName(), currentIncidentId);

                    notifyDispatcher(
                        EmergencyOntology.PERF_RESOLVED, currentIncidentId);

                    currentIncidentId = null;
                    state = State.RETURNING;
                    // Retour position actuelle (symbolique)
                    targetX = posX;
                    targetY = posY;
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
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.CAP_BIOHAZARD_CONTAINMENT); // RÔLE AGR
            sd.setName("HazmatUnit");                                 // GROUPE AGR
            dfd.addServices(sd);
            DFService.register(this, dfd);
            System.out.printf("[BCU:%s] Enregistré DF (rôle=%s)%n",
                    getLocalName(), EmergencyOntology.CAP_BIOHAZARD_CONTAINMENT);
        } catch (Exception e) {
            System.err.println("[BCU:" + getLocalName()
                    + "] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[BCU:" + getLocalName() + "] Arrêté.");
    }
}
