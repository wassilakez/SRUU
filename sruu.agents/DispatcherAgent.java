package sruu.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import sruu.ontology.EmergencyOntology;
import sruu.ontology.Incident;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LIEN COURS §3.1-C : implémente FIPA Contract Net (enchères + négociation).
 * LIEN COURS §3.2   : organisation DF — rôles et groupes AGR.
 * LIEN COURS §3.3   : raisonnement BDI — Fonction d'Utilité.
 *
 * FONCTION D'UTILITE (diapo §3.3) :
 *   U(g) = P(g|B) × V(g) − C(g)
 *   traduit en :
 *   U(u,i) = w1*(1/dist) + w2*typeMatch + w3*(1-load) + w4*(sev/3) + w5*(1/eta)
 *   w1=0.35  w2=0.30  w3=0.15  w4=0.15  w5=0.05   somme=1.0
 *
 * COORDINATION (diapo §3.1-A) :
 *   Contrainte de capacité : chaque incident ne peut avoir qu'UNE unité assignée.
 *   Si l'unité abandonne (ABORT) → réaffectation = nouvelle enchère.
 */
public class DispatcherAgent extends Agent {

    // Pondérations de la Fonction d'Utilité
    private static final double W_DISTANCE = 0.35;
    private static final double W_TYPE     = 0.30;
    private static final double W_LOAD     = 0.15;
    private static final double W_SEVERITY = 0.15;
    private static final double W_ETA      = 0.05;

    // Délai d'attente des PROPOSE (timeout Contract Net)
    private static final long CFP_TIMEOUT_MS = 3000;

    // Incidents actifs : incidentId → Incident
    private final Map<String, Incident>           activeIncidents = new ConcurrentHashMap<>();
    // Propositions en attente : incidentId → liste de messages PROPOSE
    private final Map<String, List<ACLMessage>>   proposalsMap    = new ConcurrentHashMap<>();
    // Unités occupées : nomUnite → incidentId
    private final Map<String, String>             unitAssignments = new ConcurrentHashMap<>();

    @Override
    protected void setup() {
        System.out.println("[DISPATCHER] Démarrage...");
        registerToDF();

        // ── Comportement 1 : recevoir les alertes des capteurs ────────────
        // LIEN §3.1-A : synchronisation — on attend le signal INFORM du capteur
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchOntology(EmergencyOntology.ONTOLOGY_NAME)
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleSensorAlert(msg);
                } else {
                    block(); // §3.1-A : pas de busy-waiting — block() suspend proprement
                }
            }
        });

        // ── Comportement 2 : recevoir les PROPOSE des unités ─────────────
        // LIEN §3.1-C : étape 2 du Contract Net
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE));
                if (msg != null) {
                    handleProposal(msg);
                } else {
                    block();
                }
            }
        });

        // ── Comportement 3 : recevoir ARRIVED / RESOLVED / ABORT ─────────
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.not(
                        MessageTemplate.MatchOntology(EmergencyOntology.ONTOLOGY_NAME))
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleUnitUpdate(msg);
                } else {
                    block();
                }
            }
        });

        System.out.println("[DISPATCHER] Prêt — en attente d'incidents.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // ENREGISTREMENT DF — LIEN §3.2 (pages jaunes)
    // ════════════════════════════════════════════════════════════════════════
    private void registerToDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            // LIEN §3.2 AGR : sd.setType = le RÔLE, sd.setName = le GROUPE
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.SERVICE_DISPATCHER); // Rôle
            sd.setName("SRUU-Dispatcher");                    // Groupe
            dfd.addServices(sd);
            DFService.register(this, dfd);
            System.out.println("[DISPATCHER] Enregistré dans le DF (rôle=DispatcherService).");
        } catch (Exception e) {
            System.err.println("[DISPATCHER] Erreur DF : " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TRAITEMENT ALERTE CAPTEUR
    // ════════════════════════════════════════════════════════════════════════
    private void handleSensorAlert(ACLMessage msg) {
        String content = msg.getContent();
        if (content == null || content.isBlank()) return;

        // FIX : l'incidentId peut venir du content OU du conversation-id
        String incidentId = msg.getConversationId();

        // Si conversation-id est null → lire l'ID depuis le content du message
        if (incidentId == null || incidentId.isBlank()) {
            incidentId = EmergencyOntology.get(content, EmergencyOntology.KEY_INCIDENT_ID);
        }

        // Si toujours null → générer un ID de secours
        if (incidentId == null || incidentId.isBlank()) {
            incidentId = "INC-AUTO-" + System.currentTimeMillis();
        }

        // Lire le type, sévérité, coordonnées
        String type = EmergencyOntology.get(content, EmergencyOntology.KEY_INCIDENT_TYPE);
        if (type == null || type.isBlank()) return; // message invalide

        int severity;
        int x, y;
        try {
            severity = Integer.parseInt(EmergencyOntology.get(content, EmergencyOntology.KEY_SEVERITY));
            x        = Integer.parseInt(EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X));
            y        = Integer.parseInt(EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y));
        } catch (NumberFormatException e) {
            System.err.println("[DISPATCHER] Message mal formé : " + content);
            return;
        }

        Incident incident = new Incident(type, severity, x, y);
        activeIncidents.put(incidentId, incident);
        proposalsMap.put(incidentId, new ArrayList<>());

        System.out.printf("[DISPATCHER] Incident reçu : %s%n", incident);

        notifyLogger("INCIDENT_DETECTED", incidentId, content);
        requestTrafficCorridor(incidentId, x, y);
        launchContractNet(incidentId, type, severity, x, y);
    }
    // ════════════════════════════════════════════════════════════════════════
    // FIPA CONTRACT NET — LIEN §3.1-C
    // Étapes : CFP → (attente timeout) → évaluation PROPOSE → ACCEPT/REJECT
    // ════════════════════════════════════════════════════════════════════════
    private void launchContractNet(
            String incidentId, String type, int severity, int x, int y) {

        AID[] candidates = findCandidateUnits(type); // §3.2 : recherche DF par rôle

        if (candidates.length == 0) {
            System.out.println("[DISPATCHER] Aucune unité disponible pour " + incidentId);
            notifyLogger("NO_UNIT_AVAILABLE", incidentId, "type=" + type);
            return;
        }

        // ── Étape 1 : Envoyer CFP à toutes les unités candidates ──────────
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        for (AID aid : candidates) cfp.addReceiver(aid);
        cfp.setConversationId(incidentId);
        cfp.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_INCIDENT_ID,   incidentId,
            EmergencyOntology.KEY_INCIDENT_TYPE, type,
            EmergencyOntology.KEY_SEVERITY,      String.valueOf(severity),
            EmergencyOntology.KEY_COORD_X,       String.valueOf(x),
            EmergencyOntology.KEY_COORD_Y,       String.valueOf(y)
        ));
        cfp.setReplyByDate(new Date(System.currentTimeMillis() + CFP_TIMEOUT_MS));
        send(cfp);

        System.out.printf("[DISPATCHER] CFP envoyé à %d unité(s) pour %s%n",
                candidates.length, incidentId);

        // ── Attendre le timeout puis évaluer (§3.1-C : pas de Thread.sleep) ─
        // WakerBehaviour = équivalent à un timer non-bloquant
        addBehaviour(new WakerBehaviour(this, CFP_TIMEOUT_MS) {
            @Override
            protected void onWake() {
                evaluateProposals(incidentId);
            }
        });
    }

    // ── Étape 2 : stocker les PROPOSE reçus ──────────────────────────────
    private void handleProposal(ACLMessage msg) {
        String incidentId = msg.getConversationId();
        List<ACLMessage> proposals = proposalsMap.get(incidentId);
        if (proposals != null) {
            proposals.add(msg);
            System.out.printf("[DISPATCHER] PROPOSE reçu de %s pour %s%n",
                    msg.getSender().getLocalName(), incidentId);
        }
    }

    // ── Étapes 3+4 : évaluer + ACCEPT/REJECT ─────────────────────────────
    // LIEN §3.3 BDI : on applique la Fonction d'Utilité pour choisir
    private void evaluateProposals(String incidentId) {
        Incident incident = activeIncidents.get(incidentId);
        if (incident == null) return;

        List<ACLMessage> proposals =
            proposalsMap.getOrDefault(incidentId, Collections.emptyList());

        if (proposals.isEmpty()) {
            System.out.println("[DISPATCHER] Aucune proposition reçue pour " + incidentId);
            notifyLogger("NO_PROPOSAL_RECEIVED", incidentId, "");
            return;
        }

        // Trouver la meilleure offre selon U(u,i)
        ACLMessage best      = null;
        double     bestScore = -1;

        for (ACLMessage p : proposals) {
            double score = computeUtility(p, incident);
            System.out.printf("[DISPATCHER]   Score %-15s = %.4f%n",
                    p.getSender().getLocalName(), score);
            if (score > bestScore) {
                bestScore = score;
                best      = p;
            }
        }

        if (best == null) return;

        // ── ACCEPT_PROPOSAL au gagnant ────────────────────────────────────
        ACLMessage accept = best.createReply();
        accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        accept.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_INCIDENT_ID, incidentId,
            EmergencyOntology.KEY_COORD_X,     String.valueOf(incident.getX()),
            EmergencyOntology.KEY_COORD_Y,     String.valueOf(incident.getY()),
            EmergencyOntology.KEY_INCIDENT_TYPE, incident.getType()
        ));
        send(accept);

        String winner = best.getSender().getLocalName();
        unitAssignments.put(winner, incidentId);
        incident.setStatus(Incident.Status.ASSIGNED);
        incident.setAssignedUnit(winner);

        System.out.printf("[DISPATCHER] ACCEPT_PROPOSAL → %s (U=%.4f) pour %s%n",
                winner, bestScore, incidentId);
        notifyLogger("UNIT_ASSIGNED", incidentId,
                "unit=" + winner + ";score=" + String.format("%.4f", bestScore));

        // ── REJECT_PROPOSAL aux perdants ──────────────────────────────────
        for (ACLMessage other : proposals) {
            if (other != best) {
                ACLMessage reject = other.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("incidentId=" + incidentId);
                send(reject);
            }
        }

        // ── REQUEST : ordre de déplacement à l'unité gagnante ────────────
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(best.getSender());
        request.setConversationId(incidentId);
        request.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_INCIDENT_ID,   incidentId,
            EmergencyOntology.KEY_INCIDENT_TYPE, incident.getType(),
            EmergencyOntology.KEY_COORD_X,       String.valueOf(incident.getX()),
            EmergencyOntology.KEY_COORD_Y,       String.valueOf(incident.getY())
        ));
        send(request);

        // Nettoyer
        proposalsMap.remove(incidentId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // FONCTION D'UTILITÉ — LIEN §3.3 BDI
    // U(g) = P(g|B) × V(g) − C(g)
    //
    // Ici :
    //   P(g|B)  ≈ typeMatch    (probabilité de succès selon le type d'unité)
    //   V(g)    ≈ w1*(1/dist)  (valeur = être proche)
    //   C(g)    ≈ w3*load      (coût = charge de travail actuelle)
    //
    // Formule complète :
    //   U = 0.35*(28/(d+1)) + 0.30*typeMatch + 0.15*(1-load)
    //      + 0.15*(sev/3)   + 0.05*(60/(eta+1))
    // ════════════════════════════════════════════════════════════════════════
    private double computeUtility(ACLMessage proposal, Incident incident) {
        String content = proposal.getContent();
        Map<String, String> m = EmergencyOntology.deserialize(content);

        try {
            double ux   = Double.parseDouble(
                m.getOrDefault(EmergencyOntology.KEY_COORD_X, "0"));
            double uy   = Double.parseDouble(
                m.getOrDefault(EmergencyOntology.KEY_COORD_Y, "0"));
            double eta  = Double.parseDouble(
                m.getOrDefault(EmergencyOntology.KEY_ETA, "10"));
            String unit = m.getOrDefault(EmergencyOntology.KEY_UNIT_TYPE, "");

            double dist      = Math.sqrt(
                Math.pow(ux - incident.getX(), 2) + Math.pow(uy - incident.getY(), 2));
            double fDist     = 28.0 / (dist + 1);
            double fType     = computeTypeMatch(unit, incident.getType());
            double fLoad     = unitAssignments.containsValue(
                proposal.getSender().getLocalName()) ? 0.7 : 0.0;
            double fSeverity = incident.getSeverity() / 3.0;
            double fEta      = 60.0 / (eta + 1);

            return W_DISTANCE * fDist
                 + W_TYPE     * fType
                 + W_LOAD     * (1 - fLoad)
                 + W_SEVERITY * fSeverity
                 + W_ETA      * fEta;

        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * typeMatch = P(succès | croyance sur le type de l'unité)
     * LIEN §3.3 : c'est la composante "croyances" (B) du BDI.
     */
    private double computeTypeMatch(String unitType, String incidentType) {
        switch (incidentType) {
            case EmergencyOntology.INCIDENT_MEDICAL:
                return "AMBULANCE".equals(unitType) ? 1.0 : 0.3;
            case EmergencyOntology.INCIDENT_FIRE:
                return "FIRETRUCK".equals(unitType) ? 1.0 : 0.4;
            case EmergencyOntology.INCIDENT_STRUCTURAL_COLLAPSE:
                return "FIRETRUCK".equals(unitType) ? 0.9
                     : "POLICE".equals(unitType)    ? 0.6 : 0.3;
            case EmergencyOntology.INCIDENT_BIOHAZARD:
            case EmergencyOntology.INCIDENT_CRYOGENIC_LEAK:
                return "BCU".equals(unitType) ? 1.0 : 0.1;
            default:
                return 0.5;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MISES À JOUR DES UNITÉS (ARRIVED / RESOLVED / ABORT)
    // LIEN §3.1-A : synchronisation temporelle + réaffectation dynamique
    // ════════════════════════════════════════════════════════════════════════
    private void handleUnitUpdate(ACLMessage msg) {
        String content = msg.getContent();
        String convId  = msg.getConversationId();
        String sender  = msg.getSender().getLocalName();
        if (content == null) return;

        if (content.contains(EmergencyOntology.PERF_ARRIVED)) {
            // §3.1-A synchronisation : l'agent B sait que A est arrivé
            System.out.printf("[DISPATCHER] %s ARRIVÉ sur %s%n", sender, convId);
            Incident inc = activeIncidents.get(convId);
            if (inc != null) inc.setStatus(Incident.Status.IN_PROGRESS);
            notifyLogger("UNIT_ARRIVED", convId, "unit=" + sender);

            // Si MEDICAL → demander orientation hôpital
            if (inc != null
                    && EmergencyOntology.INCIDENT_MEDICAL.equals(inc.getType())) {
                requestHospitalGuidance(convId, sender, inc.getX(), inc.getY());
            }

        } else if (content.contains(EmergencyOntology.PERF_RESOLVED)) {
            // Incident résolu
            System.out.printf("[DISPATCHER] Incident %s RÉSOLU par %s%n", convId, sender);
            Incident inc = activeIncidents.remove(convId);
            if (inc != null) {
                inc.setStatus(Incident.Status.RESOLVED);
                inc.setResolvedAt(System.currentTimeMillis());
            }
            unitAssignments.remove(sender);
            notifyLogger("INCIDENT_RESOLVED", convId,
                    "unit=" + sender + ";responseTime="
                    + (inc != null ? inc.responseTimeMs() : -1));

        } else if (content.contains(EmergencyOntology.PERF_ABORT)) {
            // §3.1-A coordination : ABORT → réaffectation dynamique
            System.out.printf(
                "[DISPATCHER] ABORT de %s pour %s — réaffectation...%n",
                sender, convId);
            notifyLogger("ABORT_RECEIVED", convId, "unit=" + sender);
            unitAssignments.remove(sender);

            Incident inc = activeIncidents.get(convId);
            if (inc != null) {
                inc.setStatus(Incident.Status.PENDING);
                inc.setAssignedUnit(null);
                // Relancer le Contract Net = nouvelle enchère
                launchContractNet(
                    convId, inc.getType(), inc.getSeverity(),
                    inc.getX(), inc.getY());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SERVICES AUXILIAIRES
    // ════════════════════════════════════════════════════════════════════════

    /** Demande un corridor au TrafficController (§3.1-A synchronisation) */
    private void requestTrafficCorridor(String incidentId, int x, int y) {
        AID trafficAID = findServiceAgent(EmergencyOntology.SERVICE_TRAFFIC);
        if (trafficAID == null) return;

        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.addReceiver(trafficAID);
        req.setConversationId(incidentId);
        req.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_INCIDENT_ID, incidentId,
            EmergencyOntology.KEY_COORD_X,     String.valueOf(x),
            EmergencyOntology.KEY_COORD_Y,     String.valueOf(y)
        ));
        send(req);
    }

    /** Demande orientation hôpital au MedicalCoordinator (§3.1-B coopération) */
    private void requestHospitalGuidance(
            String incidentId, String unitName, int x, int y) {
        AID medCoordAID = findServiceAgent(EmergencyOntology.SERVICE_MEDICAL_COORD);
        if (medCoordAID == null) return;

        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.addReceiver(medCoordAID);
        req.setConversationId(incidentId);
        req.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_INCIDENT_ID, incidentId,
            "unitName",                         unitName,
            EmergencyOntology.KEY_COORD_X,      String.valueOf(x),
            EmergencyOntology.KEY_COORD_Y,      String.valueOf(y)
        ));
        send(req);
    }

    /** Notifie le Logger de chaque événement (agent passif §3.2) */
    private void notifyLogger(String eventType, String incidentId, String details) {
        AID loggerAID = findServiceAgent(EmergencyOntology.SERVICE_LOGGER);
        if (loggerAID == null) return;

        ACLMessage log = new ACLMessage(ACLMessage.INFORM);
        log.addReceiver(loggerAID);
        log.setConversationId(EmergencyOntology.PERF_LOG_EVENT);
        log.setContent(EmergencyOntology.serialize(
            "eventType",  eventType,
            "incidentId", incidentId,
            "details",    details,
            "timestamp",  String.valueOf(System.currentTimeMillis())
        ));
        send(log);
    }

    // ── Recherche DF par rôle (§3.2 AGR — jamais par nom hardcodé) ────────
    private AID[] findCandidateUnits(String incidentType) {
        List<String> caps = new ArrayList<>();
        switch (incidentType) {
            case EmergencyOntology.INCIDENT_FIRE:
                caps.add(EmergencyOntology.CAP_FIRE);
                caps.add(EmergencyOntology.CAP_CROWD_CONTROL);
                break;
            case EmergencyOntology.INCIDENT_MEDICAL:
                caps.add(EmergencyOntology.CAP_MEDICAL);
                break;
            case EmergencyOntology.INCIDENT_STRUCTURAL_COLLAPSE:
                caps.add(EmergencyOntology.CAP_RESCUE);
                caps.add(EmergencyOntology.CAP_PERIMETER);
                break;
            case EmergencyOntology.INCIDENT_BIOHAZARD:
            case EmergencyOntology.INCIDENT_CRYOGENIC_LEAK:
                caps.add(EmergencyOntology.CAP_BIOHAZARD_CONTAINMENT);
                break;
            default:
                caps.add(EmergencyOntology.CAP_FIRE);
        }

        Set<AID> found = new HashSet<>();
        for (String cap : caps) {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType(cap);
                template.addServices(sd);
                DFAgentDescription[] results = DFService.search(this, template);
                for (DFAgentDescription r : results) found.add(r.getName());
            } catch (Exception e) {
                System.err.println("[DISPATCHER] DF erreur pour " + cap);
            }
        }
        return found.toArray(new AID[0]);
    }

    private AID findServiceAgent(String serviceType) {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(serviceType);
            template.addServices(sd);
            DFAgentDescription[] results = DFService.search(this, template);
            if (results.length > 0) return results[0].getName();
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[DISPATCHER] Arrêté.");
    }
}
