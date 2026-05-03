package sruu.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import sruu.ontology.EmergencyOntology;
import sruu.ontology.Incident;

import java.util.Random;

/**
 * Agent Capteur — point d'entrée des incidents.
 * Coordonnées fixes passées en arguments : jade.Boot sensor1:sruu.agents.SensorAgent(15,20)
 */
public class SensorAgent extends Agent {

    private int sensorX, sensorY;
    private AID dispatcherAID;
    private final Random rng = new Random();

    private static final String[] INCIDENT_TYPES = {
        EmergencyOntology.INCIDENT_FIRE,
        EmergencyOntology.INCIDENT_MEDICAL,
        EmergencyOntology.INCIDENT_STRUCTURAL_COLLAPSE,
        EmergencyOntology.INCIDENT_BIOHAZARD,
        EmergencyOntology.INCIDENT_CRYOGENIC_LEAK
    };

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length >= 2) {
            try {
                sensorX = Integer.parseInt(args[0].toString());
                sensorY = Integer.parseInt(args[1].toString());
            } catch (NumberFormatException e) {
                sensorX = rng.nextInt(50);
                sensorY = rng.nextInt(50);
            }
        } else {
            sensorX = rng.nextInt(50);
            sensorY = rng.nextInt(50);
        }

        System.out.printf("[Sensor:%s] Démarrage @ (%d,%d)%n", getLocalName(), sensorX, sensorY);

        // Attendre 2s que le Dispatcher démarre, puis le chercher dans le DF
        addBehaviour(new WakerBehaviour(this, 2000) {
            @Override
            protected void onWake() { findDispatcher(); }
        });
    }

    private void findDispatcher() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("DispatcherService");
        template.addServices(sd);
        try {
            DFAgentDescription[] found = DFService.search(this, template);
            if (found.length > 0) {
                dispatcherAID = found[0].getName();
                System.out.println("[Sensor:" + getLocalName() + "] Dispatcher trouvé.");
                startGenerating();
            } else {
                addBehaviour(new WakerBehaviour(this, 3000) {
                    @Override protected void onWake() { findDispatcher(); }
                });
            }
        } catch (FIPAException e) {
            System.err.println("[Sensor] Erreur DF : " + e.getMessage());
        }
    }

    private void startGenerating() {
        // Premier incident après 1s
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override protected void onWake() { generateRandomIncident(); }
        });
        // Puis un incident toutes les 12s
        addBehaviour(new TickerBehaviour(this, 12000) {
            @Override
            protected void onTick() { generateRandomIncident(); }
        });
    }

    private void generateRandomIncident() {
        if (dispatcherAID == null) return;
        String type  = INCIDENT_TYPES[rng.nextInt(INCIDENT_TYPES.length)];
        int severity = rng.nextInt(3) + 1;
        Incident inc = new Incident(type, severity, sensorX, sensorY);

        ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
        alert.setOntology(EmergencyOntology.ONTOLOGY_NAME);
        alert.setLanguage(EmergencyOntology.LANGUAGE);
        alert.addReceiver(dispatcherAID);
        alert.setContent(inc.toACL());
        send(alert);
        System.out.printf("[Sensor:%s] Alerte → %s%n", getLocalName(), inc);
    }

    /** Méthode pour déclencher un incident précis lors des tests */
    public void triggerIncident(String type, int severity) {
        if (dispatcherAID == null) return;
        Incident inc = new Incident(type, severity, sensorX, sensorY);
        ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
        alert.setOntology(EmergencyOntology.ONTOLOGY_NAME);
        alert.addReceiver(dispatcherAID);
        alert.setContent(inc.toACL());
        send(alert);
    }
}
