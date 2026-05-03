package sruu;

public class Main {
    public static void main(String[] args) throws Exception {

        // IMPORTANT : séparateur = ";" SANS espace entre les agents
        String agentList =
            "dispatcher:sruu.agents.DispatcherAgent;" +
            "medCoord:sruu.agents.MedicalCoordinatorAgent;" +
            "traffic:sruu.agents.TrafficControllerAgent;" +
            "logger:sruu.agents.LoggerAgent;" +
            "sensor1:sruu.agents.SensorAgent(15,20);" +
            "sensor2:sruu.agents.SensorAgent(30,10);" +
            "amb1:sruu.agents.AmbulanceAgent(5,10);" +
            "amb2:sruu.agents.AmbulanceAgent(40,35);" +
            "fire1:sruu.agents.FireTruckAgent(15,15);" +
            "fire2:sruu.agents.FireTruckAgent(45,5);" +
            "police1:sruu.agents.PoliceAgent(20,15);" +
            "police2:sruu.agents.PoliceAgent(10,40);" +
            "bcu1:sruu.agents.BCUAgent(35,40)";

        // Lancement JADE via réflexion (évite problème module Java 9+)
        String[] jadeArgs = { "-gui", agentList };
        System.out.println("=== DEMARRAGE SRUU ===");
        Class.forName("jade.Boot")
             .getMethod("main", String[].class)
             .invoke(null, (Object) jadeArgs);
    }
}
