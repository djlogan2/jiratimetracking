import java.time.ZonedDateTime;

public class DaptivUserProject {
    private final String daptivProject;
    private final double[] hours = new double[7];

    public DaptivUserProject(String daptivProject) {
        this.daptivProject = daptivProject;
    }

    public void addSeconds(ZonedDateTime date, int seconds) {
        int day = date.getDayOfWeek().getValue() - 1;
        this.hours[day] += seconds / 3600.0;
    }

    public String getDaptivProject() {
        return daptivProject;
    }

    public double getHour(int day) {
        return this.hours[day];
    }
}
