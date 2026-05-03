package sruu.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import sruu.ontology.EmergencyOntology;

import java.util.Map;

/**
 * Agent Ambulance — Unité médicale de terrain.
 *
 * LIEN COURS §3.1-C : Contract Net — répond aux CFP avec PROPOSE (offre de service).
 * LIEN COURS §3.2 AGR : rôle = CAP_MEDICAL enregistré dans le DF.
 * LIEN COURS §3.3 BDI : décision locale = calculer l'utilité de l'offre selon
 *   la distance et le statut actuel.
 *
 * Machine à États Finis (FSM) :
 *   IDLE → EN_ROUTE → ON_SCENE → RETURNING → IDLE
 *
 * Arguments JADE : position initiale "(x,y)"  ex: AmbulanceAgent(2,3)
 */
public class AmbulanceAgent extends Agent {

    // ── État interne (FSM) ────────────────────────────────────────────────
    private enum State { IDLE, EN_ROUTE, ON_SCENE, RETURNING }

    private State  state       = State.IDLE;
    private double posX, posY;             // position courante
    private double targetX, targetY;       // destination actuelle
    private String currentIncidentId = null;
    private AID    dispatcherAID     = null;

    // Vitesse de déplacement (unités/tick)
    private static final double SPEED        = 1.0;
    private static final long   TICK_MS      = 1_000;
    // Durée de traitement sur les lieux (ms)
    private static final long   TREATMENT_MS = 5_000;

    @Override
    protected void setup() {
        // Lire la position initiale depuis les arguments JADE
        Object[] args = getArguments();
        if (args != null && args.length == 1) {
            String[] coords = args[0].toString()
                                     .replaceAll("[^0-9,]", "").split(",");
            if (coords.length == 2) {
                posX = Double.parseDouble(coords[0]);
                posY = Double.parseDouble(coords[1]);
            }
        }
        System.out.printf("[AMBULANCE:%s] Démarrage @ (%.0f,%.0f)%n",
                getLocalName(), posX, posY);

        registerToDF();

        // ── Comportement 1 : écouter CFP du Dispatcher ───────────────────
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

        // ── Comportement 2 : écouter ACCEPT_PROPOSAL / REJECT_PROPOSAL ───
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                    MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL));
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                        handleAccept(msg);
                    }
                    // REJECT_PROPOSAL : rien à faire, rester IDLE
                } else {
                    block();
                }
            }
        });

        // ── Comportement 3 : déplacement par TickBehaviour ───────────────
        // LIEN §projet : "déplacement via TickBehaviour — mise à jour incrémentale"
        addBehaviour(new TickBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() {
                if (state == State.EN_ROUTE || state == State.RETURNING) {
                    moveTowards(targetX, targetY);
                }
            }
        });

        // ── Comportement 4 : écouter orientation hôpital (MedCoord) ──────
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive(MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchConversationId(
                        EmergencyOntology.PERF_HOSPITAL_REPLY)));
                if (msg != null) {
                    handleHospitalReply(msg);
                } else {
                    block();
                }
            }
        });

        System.out.printf("[AMBULANCE:%s] Prêt — état IDLE%n", getLocalName());
    }

    // ════════════════════════════════════════════════════════════════════════
    // GESTION CFP — LIEN §3.1-C Contract Net étape 2
    // ════════════════════════════════════════════════════════════════════════
    private void handleCFP(ACLMessage cfp) {
        if (state != State.IDLE) {
            // Unité occupée → REFUSE
            ACLMessage refuse = cfp.createReply();
            refuse.setPerformative(ACLMessage.REFUSE);
            refuse.setContent(EmergencyOntology.STATUS_EN_ROUTE);
            send(refuse);
            return;
        }

        String content = cfp.getContent();
        String incType = EmergencyOntology.get(content, EmergencyOntology.KEY_INCIDENT_TYPE);

        // Ambulance spécialisée MEDICAL — offre réduite pour les autres types
        double typeBonus = EmergencyOntology.INCIDENT_MEDICAL.equals(incType) ? 1.0 : 0.3;

        double incX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        double incY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));

        double dist = distance(posX, posY, incX, incY);
        double eta  = dist / SPEED;  // en ticks (secondes)

        // Score d'utilité local (BDI §3.3) — transmis au Dispatcher qui en fera
        // la comparaison avec les autres offres
        double utilityScore = typeBonus / (1 + dist);

        System.out.printf("[AMBULANCE:%s] CFP reçu pour %s — dist=%.1f score=%.3f%n",
                getLocalName(), cfp.getConversationId(), dist, utilityScore);

        ACLMessage propose = cfp.createReply();
        propose.setPerformative(ACLMessage.PROPOSE);
        propose.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_UNIT_TYPE,   "AMBULANCE",
            EmergencyOntology.KEY_UNIT_STATUS, EmergencyOntology.STATUS_IDLE,
            EmergencyOntology.KEY_COORD_X,     String.valueOf((int) posX),
            EmergencyOntology.KEY_COORD_Y,     String.valueOf((int) posY),
            EmergencyOntology.KEY_ETA,         String.valueOf((int) eta)
        ));
        send(propose);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ACCEPTATION — passer en EN_ROUTE
    // ════════════════════════════════════════════════════════════════════════
    private void handleAccept(ACLMessage accept) {
        String content = accept.getContent();
        currentIncidentId = accept.getConversationId();
        targetX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        targetY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));

        state = State.EN_ROUTE;
        System.out.printf("[AMBULANCE:%s] ACCEPTED → EN_ROUTE vers (%.0f,%.0f) [%s]%n",
                getLocalName(), targetX, targetY, currentIncidentId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // DÉPLACEMENT INCRÉMENTAL — LIEN §projet TickBehaviour
    // ════════════════════════════════════════════════════════════════════════
    private void moveTowards(double tx, double ty) {
        double dx = tx - posX;
        double dy = ty - posY;
        double d  = Math.sqrt(dx * dx + dy * dy);

        if (d <= SPEED) {
            // Arrivée à destination
            posX = tx;
            posY = ty;
            onArrived();
        } else {
            posX += SPEED * dx / d;
            posY += SPEED * dy / d;
        }
    }

    private void onArrived() {
        if (state == State.EN_ROUTE) {
            state = State.ON_SCENE;
            System.out.printf("[AMBULANCE:%s] ARRIVÉ sur les lieux %s%n",
                    getLocalName(), currentIncidentId);

            // Notifier le Dispatcher (LIEN §3.1-A synchronisation)
            notifyDispatcher(EmergencyOntology.PERF_ARRIVED, currentIncidentId);

            // Traitement sur les lieux : WakerBehaviour non bloquant
            addBehaviour(new WakerBehaviour(this, TREATMENT_MS) {
                @Override
                protected void onWake() {
                    finishTreatment();
                }
            });

        } else if (state == State.RETURNING) {
            state = State.IDLE;
            System.out.printf("[AMBULANCE:%s] Retour base — IDLE%n", getLocalName());
        }
    }

    private void finishTreatment() {
        state = State.RETURNING;
        System.out.printf("[AMBULANCE:%s] Traitement terminé → RESOLVED [%s]%n",
                getLocalName(), currentIncidentId);

        // Notifier le Dispatcher : incident résolu
        notifyDispatcher(EmergencyOntology.PERF_RESOLVED, currentIncidentId);

        // Retour à la base (position initiale 0,0 symbolique — on reste sur place)
        targetX = posX;
        targetY = posY;
        state   = State.IDLE;
        currentIncidentId = null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // RÉPONSE ORIENTATION HÔPITAL
    // ════════════════════════════════════════════════════════════════════════
    private void handleHospitalReply(ACLMessage msg) {
        Map<String, String> m = EmergencyOntology.deserialize(msg.getContent());
        String hospital = m.getOrDefault(EmergencyOntology.KEY_HOSPITAL_NAME, "inconnu");
        String beds     = m.getOrDefault(EmergencyOntology.KEY_BEDS_AVAILABLE, "?");
        System.out.printf("[AMBULANCE:%s] → Hôpital assigné : %s (lits restants : %s)%n",
                getLocalName(), hospital, beds);
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
            sd.setType(EmergencyOntology.CAP_MEDICAL); // RÔLE AGR
            sd.setName("MedicalUnit");                 // GROUPE AGR
            dfd.addServices(sd);
            DFService.register(this, dfd);
            System.out.printf("[AMBULANCE:%s] Enregistré DF (rôle=%s)%n",
                    getLocalName(), EmergencyOntology.CAP_MEDICAL);
        } catch (Exception e) {
            System.err.println("[AMBULANCE:" + getLocalName()
                    + "] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[AMBULANCE:" + getLocalName() + "] Arrêté.");
    }
}