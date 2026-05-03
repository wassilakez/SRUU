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
 * Agent Unité de Police — Sécurisation périmètre et contrôle de foule.
 *
 * LIEN COURS §3.1-A : coordination — sécurise le périmètre ET diffuse
 *   PERIMETER_SECURE pour débloquer les autres unités en attente.
 * LIEN COURS §3.2 AGR : deux rôles DF : CAP_CROWD_CONTROL et CAP_PERIMETER.
 *
 * FSM :
 *   PATROLLING (IDLE) → EN_ROUTE → SECURING → RETURNING → PATROLLING
 *
 * Comportement spécial :
 *   - En état PATROLLING : déplacement aléatoire simulant une patrouille.
 *   - En état SECURING : diffuse PERIMETER_SECURE après sécurisation.
 *
 * Arguments JADE : position initiale "(x,y)"
 */
public class PoliceAgent extends Agent {

    private enum State { PATROLLING, EN_ROUTE, SECURING, RETURNING }

    private static final double SPEED           = 1.5;
    private static final long   TICK_MS         = 1_000;
    private static final long   PATROL_TICK_MS  = 4_000;  // changement direction patrouille
    private static final long   SECURE_TIME_MS  = 4_000;  // durée sécurisation périmètre
    private static final int    PATROL_RADIUS   = 3;

    private State  state             = State.PATROLLING;
    private double posX, posY;
    private double baseX, baseY;          // position de départ (base de patrouille)
    private double targetX, targetY;
    private String currentIncidentId = null;
    private AID    dispatcherAID     = null;

    @Override
    protected void setup() {
    	Object[] args = getArguments();
    	if (args != null && args.length >= 2) {
    	    try {
    	        posX = Double.parseDouble(args[0].toString());
    	        posY = Double.parseDouble(args[1].toString());
    	        baseX = posX;
    	        baseY = posY;
    	    } catch (NumberFormatException e) {}
    	}
        System.out.printf("[POLICE:%s] Démarrage @ (%.0f,%.0f)%n",
                getLocalName(), posX, posY);

        registerToDF();

        // ── Comportement 1 : patrouille aléatoire quand IDLE ─────────────
        // LIEN §projet : "comportement de patrouille lorsqu'il est inactif"
        addBehaviour(new TickerBehaviour(this, PATROL_TICK_MS) {
            @Override
            protected void onTick() {
                if (state == State.PATROLLING) {
                    patrol();
                }
            }
        });

        // ── Comportement 2 : déplacement ─────────────────────────────────
        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() {
                if (state == State.EN_ROUTE || state == State.RETURNING) {
                    moveTowards(targetX, targetY);
                }
            }
        });

        // ── Comportement 3 : écouter CFP du Dispatcher ───────────────────
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

        // ── Comportement 4 : écouter ACCEPT / REJECT ─────────────────────
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
                    System.out.printf("[POLICE:%s] Proposition rejetée.%n",
                            getLocalName());
                } else {
                    block();
                }
            }
        });

        System.out.printf("[POLICE:%s] Prêt — PATROLLING%n", getLocalName());
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATROUILLE — comportement IDLE de la police
    // ════════════════════════════════════════════════════════════════════════
    private void patrol() {
        // Déplacement aléatoire dans un rayon autour de la base
        double angle = Math.random() * 2 * Math.PI;
        double r     = Math.random() * PATROL_RADIUS;
        double newX  = Math.max(0, Math.min(19, baseX + r * Math.cos(angle)));
        double newY  = Math.max(0, Math.min(19, baseY + r * Math.sin(angle)));
        posX = newX;
        posY = newY;
        System.out.printf("[POLICE:%s] Patrouille → (%.1f,%.1f)%n",
                getLocalName(), posX, posY);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CFP
    // ════════════════════════════════════════════════════════════════════════
    private void handleCFP(ACLMessage cfp) {
        if (state != State.PATROLLING) {
            ACLMessage refuse = cfp.createReply();
            refuse.setPerformative(ACLMessage.REFUSE);
            refuse.setContent("BUSY");
            send(refuse);
            return;
        }

        String content = cfp.getContent();
        String incType = EmergencyOntology.get(content, EmergencyOntology.KEY_INCIDENT_TYPE);

        // Police spécialisée périmètre FIRE et STRUCTURAL_COLLAPSE
        double typeBonus;
        switch (incType) {
            case EmergencyOntology.INCIDENT_FIRE:
            case EmergencyOntology.INCIDENT_STRUCTURAL_COLLAPSE:
                typeBonus = 1.0; break;
            default:
                typeBonus = 0.3; break;
        }

        double incX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        double incY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));
        double dist = distance(posX, posY, incX, incY);
        double eta  = dist / SPEED;

        System.out.printf("[POLICE:%s] CFP pour %s type=%s dist=%.1f%n",
                getLocalName(), cfp.getConversationId(), incType, dist);

        ACLMessage propose = cfp.createReply();
        propose.setPerformative(ACLMessage.PROPOSE);
        propose.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_UNIT_TYPE,   "POLICE",
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
    	if (state != State.PATROLLING) {
            ACLMessage refuse = accept.createReply();
            refuse.setPerformative(ACLMessage.FAILURE);
            refuse.setContent("BUSY");
            send(refuse);
            System.out.printf("[POLICE:%s] REFUSE (déjà occupé) pour %s%n", getLocalName(), accept.getConversationId());
            return;
        }
        String content = accept.getContent();
        currentIncidentId = accept.getConversationId();
        targetX = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
        targetY = Double.parseDouble(
            EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));

        state = State.EN_ROUTE;
        System.out.printf("[POLICE:%s] ACCEPTED → EN_ROUTE (%.0f,%.0f) [%s]%n",
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
            state = State.SECURING;
            System.out.printf("[POLICE:%s] ARRIVÉ — sécurisation périmètre [%s]%n",
                    getLocalName(), currentIncidentId);

            notifyDispatcher(EmergencyOntology.PERF_ARRIVED, currentIncidentId);

            // Sécurisation périmètre — durée fixe puis PERIMETER_SECURE
            final String incId = currentIncidentId;
            addBehaviour(new WakerBehaviour(this, SECURE_TIME_MS) {
                @Override
                protected void onWake() {
                    securePerimeter(incId);
                }
            });

        } else if (state == State.RETURNING) {
            posX  = baseX;
            posY  = baseY;
            state = State.PATROLLING;
            System.out.printf("[POLICE:%s] Retour base — PATROLLING%n", getLocalName());
        }
    }

    /**
     * Diffuse PERIMETER_SECURE à toutes les unités médicales en attente.
     * LIEN §projet : "débloque l'accès sécurisé pour d'autres unités"
     */
    private void securePerimeter(String incidentId) {
        System.out.printf("[POLICE:%s] PERIMETER_SECURE diffusé [%s]%n",
                getLocalName(), incidentId);

        notifyDispatcher(EmergencyOntology.PERF_PERIMETER_SECURE
                + ";incidentId=" + incidentId, incidentId);
        notifyDispatcher(EmergencyOntology.PERF_RESOLVED, incidentId);

        currentIncidentId = null;
        state = State.RETURNING;
        targetX = baseX;
        targetY = baseY;
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
            // Double rôle AGR
            ServiceDescription sd1 = new ServiceDescription();
            sd1.setType(EmergencyOntology.CAP_CROWD_CONTROL);
            sd1.setName("PoliceUnit");
            ServiceDescription sd2 = new ServiceDescription();
            sd2.setType(EmergencyOntology.CAP_PERIMETER);
            sd2.setName("PoliceUnit");
            dfd.addServices(sd1);
            dfd.addServices(sd2);
            DFService.register(this, dfd);
            System.out.printf("[POLICE:%s] Enregistré DF (rôles=%s,%s)%n",
                    getLocalName(),
                    EmergencyOntology.CAP_CROWD_CONTROL,
                    EmergencyOntology.CAP_PERIMETER);
        } catch (Exception e) {
            System.err.println("[POLICE:" + getLocalName()
                    + "] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[POLICE:" + getLocalName() + "] Arrêté.");
    }
}
