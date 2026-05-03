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
 * LIEN COURS §3.1-A : Coordination distribuée.
 * Ce qu'enseigne le cours : "Signal via ACLMessage.INFORM → TACHE_A_TERMINEE"
 * Ici : le Dispatcher envoie REQUEST → TrafficController ouvre le corridor
 *        → diffuse CORRIDOR_OPEN → WakerBehaviour ferme après 60s.
 *
 * Contrainte de capacité (cours §3.1-A) :
 *   Un seul corridor peut exister par incident à la fois.
 */
public class TrafficControllerAgent extends Agent {

    private static final long CORRIDOR_TIMEOUT_MS = 60_000; // 60 secondes

    // Corridors ouverts : incidentId → expiryTime
    private final Map<String, Long> openCorridors = new HashMap<>();

    @Override
    protected void setup() {
        System.out.println("[TRAFFIC] Contrôleur de trafic démarré.");
        registerToDF();

        // CyclicBehaviour : écouter les REQUEST du Dispatcher
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage req = receive(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST));
                if (req != null) {
                    handleCorridorRequest(req);
                } else {
                    block(); // §3.1-A : pas de boucle active
                }
            }
        });
    }

    private void handleCorridorRequest(ACLMessage req) {
        String content    = req.getContent();
        String incidentId = req.getConversationId();
        String x = EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_X);
        String y = EmergencyOntology.get(content, EmergencyOntology.KEY_COORD_Y);

        long expiry = System.currentTimeMillis() + CORRIDOR_TIMEOUT_MS;
        openCorridors.put(incidentId, expiry);

        System.out.printf(
            "[TRAFFIC] Corridor ouvert pour %s → (%s,%s) expire dans 60s%n",
            incidentId, x, y);

        // Diffuser CORRIDOR_OPEN à toutes les unités
        broadcastCorridorOpen(incidentId, x, y);

        // §3.1-A synchronisation temporelle : WakerBehaviour = timer non-bloquant
        // Équivalent au "Signal via ACLMessage.INFORM" du cours mais avec délai
        addBehaviour(new WakerBehaviour(this, CORRIDOR_TIMEOUT_MS) {
            @Override
            protected void onWake() {
                openCorridors.remove(incidentId);
                System.out.printf("[TRAFFIC] Corridor FERMÉ pour %s.%n", incidentId);
                broadcastCorridorClosed(incidentId);
            }
        });
    }

    private void broadcastCorridorOpen(String incidentId, String x, String y) {
        AID[] allUnits = findAllUnits();
        if (allUnits.length == 0) return;

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        for (AID u : allUnits) msg.addReceiver(u);
        msg.setConversationId(EmergencyOntology.PERF_CORRIDOR_OPEN);
        msg.setContent(EmergencyOntology.serialize(
            EmergencyOntology.KEY_INCIDENT_ID,     incidentId,
            EmergencyOntology.KEY_CORRIDOR_TIMEOUT,
                String.valueOf(CORRIDOR_TIMEOUT_MS / 1000),
            EmergencyOntology.KEY_COORD_X, x,
            EmergencyOntology.KEY_COORD_Y, y
        ));
        send(msg);
    }

    private void broadcastCorridorClosed(String incidentId) {
        AID[] allUnits = findAllUnits();
        if (allUnits.length == 0) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        for (AID u : allUnits) msg.addReceiver(u);
        msg.setConversationId("CORRIDOR_CLOSED");
        msg.setContent("incidentId=" + incidentId + ";status=CLOSED");
        send(msg);
    }

    private AID[] findAllUnits() {
        List<AID> units = new ArrayList<>();
        String[] caps = {
            EmergencyOntology.CAP_MEDICAL,
            EmergencyOntology.CAP_FIRE,
            EmergencyOntology.CAP_CROWD_CONTROL,
            EmergencyOntology.CAP_BIOHAZARD_CONTAINMENT
        };
        for (String cap : caps) {
            try {
                DFAgentDescription t = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType(cap);
                t.addServices(sd);
                for (DFAgentDescription r : DFService.search(this, t))
                    units.add(r.getName());
            } catch (Exception ignored) {}
        }
        return units.toArray(new AID[0]);
    }

    private void registerToDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EmergencyOntology.SERVICE_TRAFFIC);
            sd.setName("TrafficController");
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (Exception e) {
            System.err.println("[TRAFFIC] Erreur DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (Exception ignored) {}
        System.out.println("[TRAFFIC] Arrêté.");
    }
}