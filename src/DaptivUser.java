import java.time.ZonedDateTime;
import java.util.HashMap;

public class DaptivUser {
    private final String author;

    private final HashMap<String, DaptivUserProject> projects = new HashMap<>();

    public DaptivUser(String author) {
        this.author = author;
    }

    public void addSeconds(String daptiveProject, ZonedDateTime date, int seconds) {
        DaptivUserProject project = projects.get(daptiveProject);
        if (project == null) {
            project = new DaptivUserProject(daptiveProject);
            projects.put(daptiveProject, project);
        }
        project.addSeconds(date, seconds);
    }

    public String getAuthor() {
        return author;
    }
    public HashMap<String, DaptivUserProject> getProjects() {
        return projects;
    }
}
