package sruu.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import sruu.ontology.EmergencyOntology;
import sruu.ontology.Incident;

import java.util.Random;

/**
 * Agent Capteur (Sensor Agent) — Point d'entrée des incidents.
 *
 * LIEN COURS §3.1-A : synchronisation temporelle — envoie INFORM au Dispatcher
 *   dès qu'un incident est détecté. Le Dispatcher synchronise dessus.
 * LIEN COURS §3.2 AGR : ne s'enregistre PAS dans le DF (pas de rôle de service),
 *   mais connaît le Dispatcher via le DF (découverte par rôle).
 *
 * Comportement :
 *   - Attend 3 s après démarrage (laisse le temps aux autres agents de s'enregistrer).
 *   - Génère un incident aléatoire toutes les 8 s (TickBehaviour).
 *   - Envoie INFORM au Dispatcher avec : type, gravité, (x,y), conversationId = incidentId.
 *   - S'arrête après MAX_INCIDENTS incidents pour ne pas surcharger la démo.
 */
public class SensorAgent extends Agent {

    private static final int  MAX_INCIDENTS      = 6;     // arrêt automatique
    private static final long TICK_INTERVAL_MS   = 8_000; // 8 s entre incidents
    private static final long STARTUP_DELAY_MS   = 3_000; // délai initial

    private static final String[] INCIDENT_TYPES = {
        EmergencyOntology.INCIDENT_FIRE,
        EmergencyOntology.INCIDENT_MEDICAL,
        EmergencyOntology.INCIDENT_STRUCTURAL_COLLAPSE,
        EmergencyOntology.INCIDENT_BIOHAZARD,
        EmergencyOntology.INCIDENT_CRYOGENIC_LEAK
    };

    private final Random rng        = new Random();
    private int          count      = 0;

    @Override
    protected void setup() {
        System.out.println("[SENSOR:" + getLocalName() + "] Démarrage...");

        // Délai initial : laisser les agents coordinateurs s'enregistrer dans le DF
        addBehaviour(new WakerBehaviour(this, STARTUP_DELAY_MS) {
            @Override
            protected void onWake() {
                System.out.println("[SENSOR:" + getLocalName()
                        + "] Début détection incidents (interval=" + TICK_INTERVAL_MS + "ms)");
                startDetectionLoop();
            }
        });
    }

    /** Démarre la boucle périodique de génération d'incidents. */
    private void startDetectionLoop() {
        addBehaviour(new TickBehaviour(this, TICK_INTERVAL_MS) {
            @Override
            protected void onTick() {
                if (count >= MAX_INCIDENTS) {
                    System.out.println("[SENSOR:" + getLocalName()
                            + "] MAX incidents atteint (" + MAX_INCIDENTS + ") — arrêt.");
                    stop();
                    return;
                }
                generateAndSendIncident();
                count++;
            }
        });
    }

    /**
     * Génère un incident aléatoire et l'envoie au Dispatcher.
     * LIEN §3.1-A : le message INFORM sert de "signal de synchronisation" —
     * le Dispatcher ne démarrera le Contract Net qu'après réception.
     */
    private void generateAndSendIncident() {
        // Choix aléatoire du type et position sur une grille 20×20
        String type     = INCIDENT_TYPES[rng.nextInt(INCIDENT_TYPES.length)];
        int    severity = rng.nextInt(3) + 1;   // 1 = faible, 2 = moyen, 3 = grave
        int    x        = rng.nextInt(20);
        int    y        = rng.nextInt(20);

        // Créer l'objet incident pour obtenir l'ID unique
        Incident incident = new Incident(type, severity, x, y);

        System.out.printf("[SENSOR:%s] Incident détecté : %s%n",
                getLocalName(), incident);

        // Trouver le Dispatcher dans le DF (§3.2 : découverte par rôle, pas par nom)
        AID dispatcher = findDispatcher();
        if (dispatcher == null) {
            System.err.println("[SENSOR:" + getLocalName()
                    + "] Dispatcher introuvable dans le DF — incident ignoré.");
            return;
        }

        // Construire le message FIPA-ACL INFORM
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(dispatcher);
        msg.setOntology(EmergencyOntology.ONTOLOGY_NAME);
        // conversationId = incidentId → permet au Dispatcher de suivre l'incident
        msg.setConversationId(incident.getId());
        msg.setContent(incident.toACLContent());
        send(msg);

        System.out.printf("[SENSOR:%s] INFORM envoyé au Dispatcher → %s%n",
                getLocalName(), incident.getId());
    }

    /** Cherche le Dispatcher dans le DF par son rôle de service. */
    private AID findDispatcher() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.SERVICE_DISPATCHER);
            template.addServices(sd);
            DFAgentDescription[] results = DFService.search(this, template);
            if (results.length > 0) return results[0].getName();
        } catch (Exception e) {
            System.err.println("[SENSOR:" + getLocalName()
                    + "] Erreur recherche DF : " + e.getMessage());
        }
        return null;
    }

    @Override
    protected void takeDown() {
        System.out.println("[SENSOR:" + getLocalName() + "] Arrêté après "
                + count + " incidents générés.");
    }
}