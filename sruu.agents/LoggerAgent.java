package sruu.agents;

import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import sruu.ontology.EmergencyOntology;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Agent PASSIF — ne modifie JAMAIS l'état du système.
 * LIEN §3.2 : rôle = SERVICE_LOGGER dans le DF.
 * Produit : sruu_log.csv (horodaté) + sruu_report.txt (métriques finales).
 */
public class LoggerAgent extends Agent {

    private static final String LOG_FILE    = "sruu_log.csv";
    private static final String REPORT_FILE = "sruu_report.txt";

    private PrintWriter logWriter;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    // Statistiques
    private int  totalIncidents    = 0;
    private int  resolvedIncidents = 0;
    private int  abortEvents       = 0;
    private long totalResponseTime = 0;
    private int  responseCount     = 0;
    private final List<String> unresolvedIds = new ArrayList<>();

    @Override
    protected void setup() {
        System.out.println("[LOGGER] Démarrage...");
        registerToDF();
        initLogFile();

        // CyclicBehaviour : écouter tous les LOG_EVENT
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchConversationId(
                    EmergencyOntology.PERF_LOG_EVENT);
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    logEvent(msg);
                } else {
                    block();
                }
            }
        });

        System.out.println("[LOGGER] Actif → " + LOG_FILE);
    }

    private void initLogFile() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, false));
            logWriter.println("timestamp;eventType;incidentId;details");
            logWriter.flush();
        } catch (IOException e) {
            System.err.println("[LOGGER] Erreur fichier : " + e.getMessage());
        }
    }

    private void logEvent(ACLMessage msg) {
        String content = msg.getContent();
        Map<String, String> m = EmergencyOntology.deserialize(content);

        String eventType  = m.getOrDefault("eventType",  "UNKNOWN");
        String incidentId = m.getOrDefault("incidentId", "-");
        String details    = m.getOrDefault("details",    "");
        String ts         = sdf.format(new Date());

        if (logWriter != null) {
            logWriter.printf("%s;%s;%s;%s%n", ts, eventType, incidentId, details);
            logWriter.flush();
        }

        System.out.printf("[LOGGER] %s | %-25s | %-8s | %s%n",
                ts, eventType, incidentId, details);

        // Mise à jour statistiques
        switch (eventType) {
            case "INCIDENT_DETECTED":
                totalIncidents++;
                unresolvedIds.add(incidentId);
                break;
            case "INCIDENT_RESOLVED":
                resolvedIncidents++;
                unresolvedIds.remove(incidentId);
                String rt = extractField(details, "responseTime");
                if (!rt.isEmpty() && !"-1".equals(rt)) {
                    try {
                        totalResponseTime += Long.parseLong(rt);
                        responseCount++;
                    } catch (NumberFormatException ignored) {}
                }
                break;
            case "ABORT_RECEIVED":
                abortEvents++;
                break;
        }
    }

    private String extractField(String str, String key) {
        for (String part : str.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(key)) return kv[1].trim();
        }
        return "";
    }

    /** Rapport final généré à l'arrêt */
    @Override
    protected void takeDown() {
        generateFinalReport();
        if (logWriter != null) logWriter.close();
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[LOGGER] Arrêté — rapport dans " + REPORT_FILE);
    }

    private void generateFinalReport() {
        try (PrintWriter rw = new PrintWriter(new FileWriter(REPORT_FILE, false))) {
            rw.println("========================================");
            rw.println("   RAPPORT FINAL — SYSTÈME SRUU");
            rw.println("   " + new Date());
            rw.println("========================================");
            rw.println();
            rw.println("--- STATISTIQUES ---");
            rw.println("Incidents détectés  : " + totalIncidents);
            rw.println("Incidents résolus   : " + resolvedIncidents);
            rw.println("Incidents en attente: " + (totalIncidents - resolvedIncidents));
            rw.println("Abandons (ABORT)    : " + abortEvents);
            rw.println();
            rw.println("--- TEMPS DE RÉPONSE ---");
            double avg = responseCount > 0
                ? (double) totalResponseTime / responseCount / 1000.0 : 0;
            rw.printf("Temps moyen         : %.2f s%n", avg);
            rw.printf("Nombre de mesures   : %d%n", responseCount);
            rw.println();
            rw.println("--- INCIDENTS NON RÉSOLUS ---");
            if (unresolvedIds.isEmpty()) {
                rw.println("Aucun.");
            } else {
                for (String id : unresolvedIds) rw.println("  • " + id);
            }
            rw.println("========================================");
            rw.flush();
        } catch (IOException e) {
            System.err.println("[LOGGER] Erreur rapport : " + e.getMessage());
        }
    }

    private void registerToDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.SERVICE_LOGGER);
            sd.setName("IncidentLogger");
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (Exception e) {
            System.err.println("[LOGGER] Erreur DF : " + e.getMessage());
        }
    }
}
