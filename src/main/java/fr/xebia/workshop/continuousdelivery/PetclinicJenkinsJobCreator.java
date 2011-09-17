package fr.xebia.workshop.continuousdelivery;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Sets.newHashSet;

import java.io.UnsupportedEncodingException;
import java.util.Map;

import javax.annotation.Nonnull;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.xebia.cloud.cloudinit.FreemarkerUtils;

/**
 * Creates a job in a Jenkins server for a "Petclinic" project hosted at https://github.com/xebia-france-training/.
 * 
 * Example:
 * 
 * <pre>
 * new PetclinicJenkinsJobCreator(&quot;http://ec2-46-137-62-232.eu-west-1.compute.amazonaws.com:8080&quot;)
 *         .create(new PetclinicProjectInstance(&quot;42&quot;))
 *         .warmUp();
 * </pre>
 */
public class PetclinicJenkinsJobCreator {

    private static final Logger logger = LoggerFactory.getLogger(PetclinicJenkinsJobCreator.class);

    private final String jenkinsUrl;

    public static void main(String[] args) {
        final PetclinicJenkinsJobCreator jobCreator = new PetclinicJenkinsJobCreator("http://ec2-79-125-33-165.eu-west-1.compute.amazonaws.com:8080");

        final int teamCount = 2;
        for (int teamId = 1; teamId <= teamCount; teamId++) {
            final Integer localTeamId = teamId;

            new Thread() {
                public void run() {
                    jobCreator.create(new PetclinicProjectInstance(localTeamId.toString())).warmUp();
                };
            }.start();
        }
    }

    public PetclinicJenkinsJobCreator(@Nonnull String jenkinsUrl) {
        this.jenkinsUrl = checkNotNull(jenkinsUrl);
        checkArgument(jenkinsUrl.startsWith("http://"), "Invalid URL provided for Jenkins server: " + jenkinsUrl);
    }

    public PostCreationActions create(@Nonnull PetclinicProjectInstance project) {
        checkNotNull(project);
        String jobConfig = createConfig(project);
        logger.info("Creating job " + project.projectName);
        sendConfig(jobConfig, project);
        return new PostCreationActions(jenkinsUrl, project);
    }

    private String createConfig(@Nonnull PetclinicProjectInstance project) {
        Map<String, Object> parameters = newHashMap();
        parameters.put("projectName", project.projectName);
        parameters.put("groupId", project.groupId);
        parameters.put("artifactId", project.artifactId);
        return FreemarkerUtils.generate(parameters, "/petclinic-jenkins-job-config.xml.fmt");
    }

    private void sendConfig(@Nonnull String jobConfig, @Nonnull PetclinicProjectInstance project) {
        HttpPost post = new HttpPost(jenkinsUrl + "/createItem?name=" + project.projectName);
        post.setEntity(httpEntityForXml(jobConfig));
        new Client().post(post);
    }

    private HttpEntity httpEntityForXml(String string) {
        try {
            return new StringEntity(string, "text/xml", "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new JobCreationException("UTF-8 not supported by the platform?!", e);
        }
    }

    public static class JobCreationException extends RuntimeException {

        private static final long serialVersionUID = -559471701873832122L;

        public JobCreationException(String message) {
            super(message);
        }

        public JobCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class Client {

        void post(HttpPost post) {
            HttpClient client = new DefaultHttpClient();
            try {
                logger.debug("Executing request {}", post.getRequestLine());

                HttpResponse response;
                try {
                    response = client.execute(post);
                } catch (Exception e) {
                    throw new JobCreationException("Could not execute request", e);
                }

                logger.debug("Response status: {}", response.getStatusLine());

                if (!newHashSet(HttpStatus.SC_OK, HttpStatus.SC_MOVED_TEMPORARILY).contains(response.getStatusLine().getStatusCode())) {
                    try {
                        logger.warn(EntityUtils.toString(response.getEntity()));
                    } catch (Exception e) {
                        logger.warn("Could not print entity");
                    }
                    throw new JobCreationException(response.getStatusLine().toString());
                }
            } finally {
                client.getConnectionManager().shutdown();
            }
        }
    }

    public static class PostCreationActions {

        private final String jenkinsUrl;
        private final PetclinicProjectInstance project;

        public PostCreationActions(String jenkinsUrl, PetclinicProjectInstance project) {
            this.jenkinsUrl = jenkinsUrl;
            this.project = project;
        }

        /**
         * Warms up the project, i.e. triggers a build so that dependencies are loaded.
         */
        public void warmUp() {
            logger.info("Warming up job " + project.projectName);
            new Client().post(new HttpPost(String.format("%s/job/%s/build", jenkinsUrl, project.projectName)));
        }
    }
}
