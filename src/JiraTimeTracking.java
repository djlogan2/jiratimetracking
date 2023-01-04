import com.fasterxml.jackson.databind.ObjectMapper;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.time.temporal.ChronoUnit.SECONDS;

@SuppressWarnings({"unchecked", "rawtypes", "SpellCheckingInspection"})
public class JiraTimeTracking {
    private String jiraurl;
    private String smtpurl;
    private String smtpport;
    private String fromaddress;
    private String defaultaddress;
    private String jirausername;
    private String jirapassword;
    private boolean test = true;
    private final HashMap<String, String> userToEmail = new HashMap<>();
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private final DateTimeFormatter dateTimeFormatter1 = DateTimeFormatter.ISO_LOCAL_DATE;
    private final HashMap<String, DaptivUser> daptivUserHashMap = new HashMap<>();
    private final ArrayList<TimeEntry> timeEntries = new ArrayList<>();
    private String jql;
    private LocalDate previousMonday;
    private LocalDate followingSunday;

    public static void main(String[] args) {
        JiraTimeTracking javaTimeTracking = new JiraTimeTracking();
        javaTimeTracking.run();
    }

    private void run() {
        try (InputStream input = new FileInputStream("timetracking.properties")) {
            Properties properties = new Properties();
            properties.load(input);
            test = Boolean.parseBoolean(properties.getProperty("test", "true"));
            jiraurl = properties.getProperty("jiraurl");
            smtpurl = properties.getProperty("smtpurl");
            smtpport = properties.getProperty("smtpport", "25");
            fromaddress = properties.getProperty("fromaddress");
            defaultaddress = properties.getProperty("defaultaddress");
            jirausername = properties.getProperty("jirausername");
            jirapassword = properties.getProperty("jirapassword");
            properties.keySet()
                    .stream()
                    .filter(key -> key.toString().startsWith("usertoemail."))
                    .map(key -> ((String) key).split("\\.")[2])
                    .distinct()
                    .forEach(suffix -> {
                        String propkey = "usertoemail.key." + suffix;
                        String propaddr = "usertoemail.addr." + suffix;
                        String key = properties.getProperty(propkey);
                        String addr = properties.getProperty(propaddr);
                        if(key == null || addr == null) {
                            System.out.println("Error adding " + propkey + "=" + key + ", " + propaddr + "=" + addr);
                        } else {
                            userToEmail.put(key, addr);
                        }
                    });
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
        LocalDate now = LocalDate.now();
        int minusDays = now.getDayOfWeek().getValue() + 6;
        previousMonday = now.minusDays(minusDays);
        followingSunday = previousMonday.plusDays(6);

        jql = "worklogDate >= '" + dateTimeFormatter1.format(previousMonday) + "' AND worklogDate <= '" + dateTimeFormatter1.format(followingSunday) + "'";

        Integer startAt = 0;
        while (startAt != null) {
            startAt = jiraCall(startAt);
        }
        timeEntries.forEach(timeEntry -> {
            DaptivUser daptivUser = daptivUserHashMap.get(timeEntry.author);
            if (daptivUser == null) {
                daptivUser = new DaptivUser(timeEntry.author);
                daptivUserHashMap.put(timeEntry.author, daptivUser);
            }
            String daptivProject = timeEntry.daptivProject == null ? null : timeEntry.daptivProject.toString();
            if (daptivProject == null) {
                if (timeEntry.issueType.equals("Bug"))
                    daptivProject = "102 Bugs: AAS";
//                else if(false)
//                    daptivProject = "112 Prod Support: AAS";
//                else if(false)
//                    daptivProject = "Non-Project Work";
                else daptivProject = "119 Enhancements: AAS";
            }
            daptivUser.addSeconds(daptivProject, timeEntry.created, timeEntry.timeSpent);
        });
        daptivUserHashMap.forEach((author, user) -> {
            String emailAddress = userToEmail.get(author);
            sendDaptivEmail(user, emailAddress);
        });
    }

    private Integer jiraCall(int startAt) {
        int total = startAt;
        int newStartAt = startAt;
        int maxResults;
        try {
            HttpRequest request1 = HttpRequest.newBuilder()
                    .uri(new URI(jiraurl + "/rest/api/latest/search?startAt=" + startAt + "&maxResults=1000&expand=changelog&jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8)))
                    .header("Authorization", getBasicAuthenticationHeader(jirausername, jirapassword))
                    .timeout(Duration.of(60, SECONDS))
                    .GET()
                    .build();
            ObjectMapper objectMapper1 = new ObjectMapper();
            HttpResponse<String> response1 = HttpClient.newBuilder().build().send(request1, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> jsonMap = objectMapper1.readValue(response1.body(), HashMap.class);
            ArrayList<LinkedHashMap> issues = (ArrayList<LinkedHashMap>) jsonMap.get("issues");
            total = jsonMap.get("total") == null ? total : (Integer) jsonMap.get("total");
            newStartAt = jsonMap.get("startAt") == null ? newStartAt : (Integer) jsonMap.get("startAt");
            maxResults = jsonMap.get("maxResults") == null ? newStartAt : (Integer) jsonMap.get("maxResults");
            newStartAt += maxResults;
            if (issues != null)
                issues.forEach(issue -> {
                    String jiraKey = (String) issue.get("key");
                    LinkedHashMap<String, Object> fields = (LinkedHashMap<String, Object>) issue.get("fields");
                    LinkedHashMap<String, Object> lhmIssuetype = (LinkedHashMap<String, Object>) fields.get("issuetype");
                    String issueType = (String) lhmIssuetype.get("name");
                    LinkedHashMap<String, Object> parent = (LinkedHashMap<String, Object>) fields.get("parent");
                    String parentKey = parent == null ? null : (String) parent.get("key");
                    Epic epic = getEpic(parentKey);
                    //--
                    try {
                        HttpRequest request2 = HttpRequest.newBuilder()
                                .uri(new URI(jiraurl + "/rest/api/latest/issue/" + jiraKey + "/worklog"))
                                .header("Authorization", getBasicAuthenticationHeader(jirausername, jirapassword))
                                .timeout(Duration.of(60, SECONDS))
                                .GET()
                                .build();
                        ObjectMapper objectMapper2 = new ObjectMapper();
                        HttpResponse<String> response2 = HttpClient.newBuilder().build().send(request2, HttpResponse.BodyHandlers.ofString());
                        Map<String, Object> jsonMap2 = objectMapper2.readValue(response2.body(), HashMap.class);
                        ArrayList<LinkedHashMap<String, Object>> worklogs = (ArrayList<LinkedHashMap<String, Object>>) jsonMap2.get("worklogs");
                        if (worklogs != null) {
                            worklogs.forEach(worklog -> {
                                LinkedHashMap<String, Object> lhmAuthor = (LinkedHashMap<String, Object>) worklog.get("author");
                                String author = (String) lhmAuthor.get("displayName");
                                String sCreated = (String) worklog.get("started");
                                ZonedDateTime created = ZonedDateTime.from(dateTimeFormatter.parse(sCreated));
                                if (!created.toLocalDate().isBefore(previousMonday) && !created.toLocalDate().isAfter(followingSunday)) {
                                    Integer timeSpent = (Integer) worklog.get("timeSpentSeconds");
                                    TimeEntry timeEntry = new TimeEntry();
                                    timeEntry.jiraKey = jiraKey;
                                    timeEntry.issueType = issueType;
                                    timeEntry.daptivProject = epic == null ? null : epic.daptiv;
                                    timeEntry.epicKey = epic == null ? null : epic.jiraKey;
                                    timeEntry.author = author;
                                    timeEntry.created = created;
                                    timeEntry.timeSpent = timeSpent;
                                    timeEntries.add(timeEntry);
                                }
                            });
                        }
                    } catch (URISyntaxException | IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    //--
                });
        } catch (URISyntaxException | IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return newStartAt < total ? newStartAt : null;
    }

    private Epic getEpic(String parentKey) {
        if (parentKey == null) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(jiraurl + "/rest/api/latest/search?jql=" + URLEncoder.encode("key=" + parentKey, StandardCharsets.UTF_8)))
                    .header("Authorization", getBasicAuthenticationHeader(jirausername, jirapassword))
                    .timeout(Duration.of(60, SECONDS))
                    .GET()
                    .build();
            ObjectMapper objectMapper = new ObjectMapper();
            HttpResponse<String> response = HttpClient.newBuilder().build().send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> jsonMap = objectMapper.readValue(response.body(), HashMap.class);
            ArrayList<LinkedHashMap> issues = (ArrayList<LinkedHashMap>) jsonMap.get("issues");
            LinkedHashMap<String, Object> issue = issues.get(0);
            String jiraKey = (String) issue.get("key");
            LinkedHashMap<String, Object> fields = (LinkedHashMap<String, Object>) issue.get("fields");
            LinkedHashMap<String, Object> lhmIssuetype = (LinkedHashMap<String, Object>) fields.get("issuetype");
            String issueType = (String) lhmIssuetype.get("name");
            if ("Epic".equals(issueType)) {
                Epic epic = new Epic();
                epic.jiraKey = jiraKey;
                Double daptiv = (Double) fields.get("customfield_10378");
                epic.daptiv = daptiv == null ? null : daptiv.intValue();
                return epic;
            } else {
                LinkedHashMap<String, Object> parent = (LinkedHashMap<String, Object>) fields.get("parent");
                String newParentKey = parent == null ? null : (String) parent.get("key");
                if (newParentKey == null) return null;
                return getEpic(newParentKey);
            }
        } catch (URISyntaxException | IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendDaptivEmail(DaptivUser daptivUser, String emailAddress) {
        String subject = "Weekly Daptiv hours for '" +
                daptivUser.getAuthor() + "' " +
                dateTimeFormatter1.format(previousMonday) + " - " + dateTimeFormatter1.format(followingSunday);
        Properties prop = new Properties();
        prop.put("mail.smtp.host", smtpurl);
        prop.put("mail.smtp.port", smtpport);
        Session session = Session.getDefaultInstance(prop);

        Message message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(fromaddress));
            if (emailAddress == null) {
                emailAddress = defaultaddress;
                subject = "UNKNOWN EMAIL: " + subject;
            } else if(test) {
                emailAddress = defaultaddress;
                subject = "TEST: " + subject;
            }
            message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(emailAddress));
            message.setSubject(subject);
            StringBuilder sb = new StringBuilder();
            sb.append("<table width='100%'>");
            sb.append("<tr><td></td><td>Mon</td><td>Tue</td><td>Wed</td><td>Thu</td><td>Fri</td><td>Sat</td><td>Sun</td></tr>");
            daptivUser.getProjects().forEach((key, value) -> {
                sb.append("<tr>");
                sb.append("<td>").append(value.getDaptivProject()).append("</td>");
                for (int x = 0; x < 7; x++)
                    sb.append("<td>").append(value.getHour(x)).append("</td>");
                sb.append("</tr>");
            });
            sb.append("</table>");
            message.setContent(sb.toString(), "text/html");
            Transport.send(message);
        } catch (MessagingException me) {
            throw new RuntimeException(me);
        }
    }

    private static String getBasicAuthenticationHeader(String username, String password) {
        String valueToEncode = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }
}
