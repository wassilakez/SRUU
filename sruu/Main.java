package sruu;

/**
 * Lance le système SRUU complet.
 * IMPORTANT : JADE exige que toutes les spécifications d'agents soient
 * dans une seule chaîne, séparées par des points-virgules ";" sans espaces.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // Une seule chaîne contenant TOUS les agents séparés par ";"
        String agents =
            "dispatcher:sruu.agents.DispatcherAgent;" +
            "medCoord:sruu.agents.MedicalCoordinatorAgent;" +
            "traffic:sruu.agents.TrafficControllerAgent;" +
            "logger:sruu.agents.LoggerAgent;" +
            "sensor1:sruu.agents.SensorAgent;" +
            "sensor2:sruu.agents.SensorAgent;" +
            "ambulance1:sruu.agents.AmbulanceAgent(2,3);" +
            "ambulance2:sruu.agents.AmbulanceAgent(15,12);" +
            "ambulance3:sruu.agents.AmbulanceAgent(8,18);" +
            "firetruck1:sruu.agents.FireTruckAgent(1,1);" +
            "firetruck2:sruu.agents.FireTruckAgent(19,19);" +
            "police1:sruu.agents.PoliceAgent(5,5);" +
            "police2:sruu.agents.PoliceAgent(14,7);" +
            "bcu1:sruu.agents.BCUAgent(10,10)";

        String[] jadeArgs = { "-gui", agents };

        System.out.println("=== DÉMARRAGE SRUU ===");
        jade.Boot.main(jadeArgs);
    }
}
