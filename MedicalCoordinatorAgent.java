package sruu.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import sruu.ontology.EmergencyOntology;
import java.util.*;

/**
 * LIEN COURS §3.1-B : Coopération — cet agent agit comme courtier.
 * Il décompose la tâche "trouver un hôpital" et la distribue.
 * LIEN §3.2 AGR : rôle = MedicalCoordinatorService dans le DF.
 */
public class MedicalCoordinatorAgent extends Agent {

    // Registre des hôpitaux : nom → [x, y, litsDisponibles]
    private final Map<String, int[]> hospitals = new LinkedHashMap<>();

    @Override
    protected void setup() {
        System.out.println("[MED_COORD] Démarrage...");
        initHospitals();
        registerToDF();

        // CyclicBehaviour (§3.1-A) : attend les requêtes en permanence
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage req = receive(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST));
                if (req != null) {
                    handleGuidanceRequest(req);
                } else {
                    block(); // Pas de busy-waiting
                }
            }
        });

        System.out.println("[MED_COORD] Prêt — "
                + hospitals.size() + " hôpitaux enregistrés.");
    }

    private void initHospitals() {
        // [x, y, lits]
        hospitals.put("CHU-Nord",       new int[]{2,  3,  10});
        hospitals.put("Clinique-Est",   new int[]{18, 5,  5});
        hospitals.put("Hopital-Centre", new int[]{10, 10, 8});
        hospitals.put("CHU-Sud",        new int[]{5,  18, 12});
    }

    private void handleGuidanceRequest(ACLMessage req) {
        String content = req.getContent();
        Map<String, String> m = EmergencyOntology.deserialize(content);

        double incX     = Double.parseDouble(
            m.getOrDefault(EmergencyOntology.KEY_COORD_X, "0"));
        double incY     = Double.parseDouble(
            m.getOrDefault(EmergencyOntology.KEY_COORD_Y, "0"));
        String unitName = m.getOrDefault("unitName", "?");

        // Trouver l'hôpital disponible le plus proche
        String bestHospital  = null;
        double bestDist      = Double.MAX_VALUE;
        boolean allSaturated = true;

        for (Map.Entry<String, int[]> e : hospitals.entrySet()) {
            int[] data = e.getValue();
            if (data[2] > 0) { // lits disponibles
                allSaturated = false;
                double d = Math.sqrt(
                    Math.pow(incX - data[0], 2) + Math.pow(incY - data[1], 2));
                if (d < bestDist) {
                    bestDist    = d;
                    bestHospital = e.getKey();
                }
            }
        }

        if (allSaturated) {
            // §3.1-B coopération : alerte le Dispatcher de la saturation
            System.out.println("[MED_COORD] SATURATION TOTALE — alerte Dispatcher.");
            alertDispatcherSaturation();

            ACLMessage fail = req.createReply();
            fail.setPerformative(ACLMessage.FAILURE);
            fail.setContent("SATURATION_TOTALE");
            send(fail);
            return;
        }

        // Décrémenter les lits
        hospitals.get(bestHospital)[2]--;

        System.out.printf(
            "[MED_COORD] %s → %s (lits restants: %d)%n",
            unitName, bestHospital, hospitals.get(bestHospital)[2]);

        ACLMessage reply = req.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setConversationId(EmergencyOntology.PERF_HOSPITAL_REPLY);
        reply.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_HOSPITAL_NAME,  bestHospital,
            EmergencyOntology.KEY_BEDS_AVAILABLE,
                String.valueOf(hospitals.get(bestHospital)[2]),
            "unitName", unitName
        ));
        send(reply);
    }

    private void alertDispatcherSaturation() {
        AID dispatcher = findDispatcher();
        if (dispatcher == null) return;
        ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
        alert.addReceiver(dispatcher);
        alert.setConversationId(EmergencyOntology.PERF_SATURATION_ALERT);
        alert.setContent("SATURATION_TOTALE;ts=" + System.currentTimeMillis());
        send(alert);
    }

    private AID findDispatcher() {
        try {
            DFAgentDescription t = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.SERVICE_DISPATCHER);
            t.addServices(sd);
            DFAgentDescription[] r = DFService.search(this, t);
            if (r.length > 0) return r[0].getName();
        } catch (Exception ignored) {}
        return null;
    }

    private void registerToDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.SERVICE_MEDICAL_COORD);
            sd.setName("MedicalCoordinator");
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (Exception e) {
            System.err.println("[MED_COORD] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[MED_COORD] Arrêté.");
    }
}