import java.time.ZonedDateTime;
import java.util.HashMap;

public class DaptivUser {
    private final String author;

    private final HashMap<String, DaptivUserProject> projects = new HashMap<>();

    public DaptivUser(String author) {
        this.author = author;
        DaptivUserProject project = new DaptivUserProject("Non-Project Work");
        projects.put("Non-Project Work", project);
        for(int x = 0 ; x < 5 ; x++) project.setHours(x, 8.0);
    }

    public void addSeconds(String daptiveProject, ZonedDateTime date, int seconds) {
        DaptivUserProject project = projects.get(daptiveProject);
        DaptivUserProject non = projects.get("Non-Project Work");
        if (project == null) {
            project = new DaptivUserProject(daptiveProject);
            projects.put(daptiveProject, project);
        }
        project.addSeconds(date, seconds);
        non.removeSeconds(date, seconds);
    }

    public String getAuthor() {
        return author;
    }
    public HashMap<String, DaptivUserProject> getProjects() {
        return projects;
    }
}
