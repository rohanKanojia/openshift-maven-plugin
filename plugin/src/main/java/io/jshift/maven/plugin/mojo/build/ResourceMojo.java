/**
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.jshift.maven.plugin.mojo.build;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.openshift.api.model.Template;
import io.jshift.generator.api.GeneratorContext;
import io.jshift.kit.build.service.docker.ImageConfiguration;
import io.jshift.kit.build.service.docker.config.ConfigHelper;
import io.jshift.kit.build.service.docker.config.handler.ImageConfigResolver;
import io.jshift.kit.build.service.docker.helper.ImageNameFormatter;
import io.jshift.kit.common.KitLogger;
import io.jshift.kit.common.ResourceFileType;
import io.jshift.kit.common.util.EnvUtil;
import io.jshift.kit.common.util.KubernetesHelper;
import io.jshift.kit.common.util.MavenUtil;
import io.jshift.kit.common.util.ResourceClassifier;
import io.jshift.kit.common.util.ResourceUtil;
import io.jshift.kit.common.util.ValidationUtil;
import io.jshift.kit.common.util.validator.ResourceValidator;
import io.jshift.kit.config.access.ClusterAccess;
import io.jshift.kit.config.access.ClusterConfiguration;
import io.jshift.kit.config.image.build.OpenShiftBuildStrategy;
import io.jshift.kit.config.resource.MappingConfig;
import io.jshift.kit.config.resource.PlatformMode;
import io.jshift.kit.config.resource.ProcessorConfig;
import io.jshift.kit.config.resource.ResourceConfig;
import io.jshift.kit.config.resource.RuntimeMode;
import io.jshift.kit.profile.Profile;
import io.jshift.kit.profile.ProfileUtil;
import io.jshift.maven.enricher.api.MavenEnricherContext;
import io.jshift.maven.enricher.api.util.KubernetesResourceUtil;
import io.jshift.maven.enricher.handler.HandlerHub;
import io.jshift.maven.plugin.enricher.EnricherManager;
import io.jshift.maven.plugin.generator.GeneratorManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;

import javax.validation.ConstraintViolationException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static io.jshift.kit.common.ResourceFileType.yaml;


/**
 * Generates or copies the Kubernetes JSON file and attaches it to the build so its
 * installed and released to maven repositories like other build artifacts.
 */
@Mojo(name = "resource", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class ResourceMojo extends AbstractJshiftMojo {

    // Filename for holding the build timestamp
    public static final String DOCKER_BUILD_TIMESTAMP = "docker/build.timestamp";

    private static final String DOCKER_IMAGE_USER = "docker.image.user";
    /**
     * The generated kubernetes and openshift manifests
     */
    @Parameter(property = "jshift.targetDir", defaultValue = "${project.build.outputDirectory}/META-INF/jshift")
    protected File targetDir;

    @Component(role = MavenFileFilter.class, hint = "default")
    private MavenFileFilter mavenFileFilter;

    @Component
    private ImageConfigResolver imageConfigResolver;

    /**
     * Folder where to find project specific files
     */
    @Parameter(property = "jshift.resourceDir", defaultValue = "${basedir}/src/main/jshift")
    private File resourceDir;

    /**
     * Folder where to find project specific files
     */
    @Parameter(property = "jshift.resourceDirOpenShiftOverride", defaultValue = "${basedir}/src/main/jshift-openshift-override")
    private File resourceDirOpenShiftOverride;

    /**
     * Environment name where resources are placed. For example, if you set this property to dev and resourceDir is the default one, plugin will look at src/main/jshift/dev
     * Same applies for resourceDirOpenShiftOverride property.
     */
    @Parameter(property = "jshift.environment")
    private String environment;

    /**
     * Should we use the project's compile-time classpath to scan for additional enrichers/generators?
     */
    @Parameter(property = "jshift.useProjectClasspath", defaultValue = "false")
    private boolean useProjectClasspath = false;

    /**
     * The jshift working directory
     */
    @Parameter(property = "jshift.workDir", defaultValue = "${project.build.directory}/jshift")
    private File workDir;

    /**
     * The jshift working directory
     */
    @Parameter(property = "jshift.workDirOpenShiftOverride", defaultValue = "${project.build.directory}/jshift-openshift-override")
    private File workDirOpenShiftOverride;

    // Resource specific configuration for this plugin
    @Parameter
    private ResourceConfig resources;

    @Parameter(property = "jshift.mode")
    private RuntimeMode runtimeMode = RuntimeMode.DEFAULT;

    // Skip resource descriptors validation
    @Parameter(property = "jshift.skipResourceValidation", defaultValue = "false")
    private Boolean skipResourceValidation;

    // Determine if the plugin should stop when a validation error is encountered
    @Parameter(property = "jshift.failOnValidationError", defaultValue = "false")
    private Boolean failOnValidationError;

    // Reusing image configuration from d-m-p
    @Parameter
    private List<ImageConfiguration> images;

    @Parameter(property = "jshift.build.switchToDeployment", defaultValue = "false")
    private Boolean switchToDeployment;
    /**
     * Profile to use. A profile contains the enrichers and generators to
     * use as well as their configuration. Profiles are looked up
     * in the classpath and can be provided as yaml files.
     * <p>
     * However, any given enricher and or generator configuration overrides
     * the information provided by a profile.
     */
    @Parameter(property = "jshift.profile")
    private String profile;

    /**
     * Enricher specific configuration configuration given through
     * to the various enrichers.
     */

    // Resource specific configuration for this plugin
    @Parameter(property = "jshift.gitRemote")
    private String gitRemote;

    @Parameter
    private ProcessorConfig enricher;

    /**
     * Configuration passed to generators
     */
    @Parameter
    private ProcessorConfig generator;

    // Whether to use replica sets or replication controller. Could be configurable
    // but for now leave it hidden.
    private boolean useReplicaSet = true;

    // The image configuration after resolving and customization
    private List<ImageConfiguration> resolvedImages;

    // Mapping for kind filenames
    @Parameter
    private List<MappingConfig> mappings;

    // Services
    private HandlerHub handlerHub;

    /**
     * Namespace to use when accessing Kubernetes or OpenShift
     */
    @Parameter(property = "jshift.namespace")
    private String namespace;

    @Parameter(property = "jshift.sidecar", defaultValue = "false")
    private Boolean sidecar;

    @Parameter(property = "jshift.skipHealthCheck", defaultValue = "false")
    private Boolean skipHealthCheck;

    /**
     * The OpenShift deploy timeout in seconds:
     * See this issue for background of why for end users on slow wifi on their laptops
     * DeploymentConfigs usually barf: https://github.com/openshift/origin/issues/10531
     *
     * Please follow also the discussion at
     * <ul>
     *     <li>https://github.com/fabric8io/fabric8-maven-plugin/pull/944#discussion_r116962969</li>
     *     <li>https://github.com/fabric8io/fabric8-maven-plugin/pull/794</li>
     * </ul>
     * and the references within it for the reason of this ridiculous long default timeout
     * (in short: Its because Docker image download times are added to the deployment time, making
     * the default of 10 minutes quite unusable if multiple images are included in the deployment).
     */
    @Parameter(property = "jshift.openshift.deployTimeoutSeconds", defaultValue = "3600")
    private Long openshiftDeployTimeoutSeconds;

    /**
     * If set to true it would set the container image reference to "", this is done to handle weird
     * behavior of Openshift 3.7 in which subsequent rollouts lead to ImagePullErr
     *
     * Please see discussion at
     * <ul>
     *     <li>https://github.com/openshift/origin/issues/18406</li>
     *     <li>https://github.com/fabric8io/fabric8-maven-plugin/issues/1130</li>
     * </ul>
     */
    @Parameter(property = "jshift.openshift.trimImageInContainerSpec", defaultValue = "false")
    private Boolean trimImageInContainerSpec;

    @Parameter(property = "jshift.openshift.generateRoute", defaultValue = "true")
    private Boolean generateRoute;

    @Parameter(property = "jshift.openshift.enableAutomaticTrigger", defaultValue = "true")
    private Boolean enableAutomaticTrigger;

    @Parameter(property = "jshift.openshift.imageChangeTrigger", defaultValue = "true")
    private Boolean enableImageChangeTrigger;

    @Parameter(property = "jshift.openshift.enrichAllWithImageChangeTrigger", defaultValue = "false")
    private Boolean erichAllWithImageChangeTrigger;

    @Parameter(property = "docker.skip.resource", defaultValue = "false")
    protected boolean skipResource;

    /**
     * The artifact type for attaching the generated resource file to the project.
     * Can be either 'json' or 'yaml'
     */
    @Parameter(property = "jshift.resourceType")
    private ResourceFileType resourceFileType = yaml;

    @Component
    private MavenProjectHelper projectHelper;

    // resourceDir when environment has been applied
    private File realResourceDir;

    /**
     * Returns the Template if the list contains a single Template only otherwise returns null
     */
    protected static Template getSingletonTemplate(KubernetesList resources) {
        // if the list contains a single Template lets unwrap it
        if (resources != null) {
            List<HasMetadata> items = resources.getItems();
            if (items != null && items.size() == 1) {
                HasMetadata singleEntity = items.get(0);
                if (singleEntity instanceof Template) {
                    return (Template) singleEntity;
                }
            }
        }
        return null;
    }

    public static File writeResourcesIndividualAndComposite(KubernetesList resources, File resourceFileBase,
        ResourceFileType resourceFileType, KitLogger log, Boolean generateRoute) throws MojoExecutionException {

        //Creating a new items list. This will be used to generate openshift.yml
        List<HasMetadata> newItemList = new ArrayList<>();

        if (!generateRoute) {

            //if flag is set false, this will remove the Route resource from resources list
            for (HasMetadata item : resources.getItems()) {
                if (item.getKind().equalsIgnoreCase("Route")) {
                    continue;
                }
                newItemList.add(item);
            }

            //update the resource with new list
            resources.setItems(newItemList);
        }

        // entity is object which will be sent to writeResource for openshift.yml
        // if generateRoute is false, this will be set to resources with new list
        // otherwise it will be set to resources with old list.
        Object entity = resources;

        // if the list contains a single Template lets unwrap it
        // in resources already new or old as per condition is set.
        // no need to worry about this for dropping Route.
        Template template = getSingletonTemplate(resources);
        if (template != null) {
            entity = template;
        }

        File file = writeResource(resourceFileBase, entity, resourceFileType);

        // write separate files, one for each resource item
        // resources passed to writeIndividualResources is also new one.
        writeIndividualResources(resources, resourceFileBase, resourceFileType, log, generateRoute);
        return file;
    }

    private static void writeIndividualResources(KubernetesList resources, File targetDir,
        ResourceFileType resourceFileType, KitLogger log, Boolean generateRoute) throws MojoExecutionException {
        for (HasMetadata item : resources.getItems()) {
            String name = KubernetesHelper.getName(item);
            if (StringUtils.isBlank(name)) {
                log.error("No name for generated item %s", item);
                continue;
            }
            String itemFile = KubernetesResourceUtil.getNameWithSuffix(name, item.getKind());

            // Here we are writing individual file for all the resources.
            // if generateRoute is false and resource is route, we should not generate it.

            if (!(item.getKind().equalsIgnoreCase("Route") && !generateRoute)) {
                File itemTarget = new File(targetDir, itemFile);
                writeResource(itemTarget, item, resourceFileType);
            }
        }
    }

    private static File writeResource(File resourceFileBase, Object entity, ResourceFileType resourceFileType)
        throws MojoExecutionException {
        try {
            return ResourceUtil.save(resourceFileBase, entity, resourceFileType);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write resource to " + resourceFileBase + ". " + e, e);
        }
    }

    public void executeInternal() throws MojoExecutionException, MojoFailureException {
        if (skipResource) {
            return;
        }

        realResourceDir = ResourceUtil.getFinalResourceDir(resourceDir, environment);
        updateKindFilenameMappings();
        try {
            lateInit();
            // Resolve the Docker image build configuration
            resolvedImages = getResolvedImages(images, log);
            if (!skip && (!isPomProject() || hasFabric8Dir())) {
                // Extract and generate resources which can be a mix of Kubernetes and OpenShift resources
                KubernetesList resources;
                for(PlatformMode platformMode : new PlatformMode[] { PlatformMode.openshift }) {
                    ResourceClassifier resourceClassifier = platformMode == PlatformMode.kubernetes ? ResourceClassifier.KUBERNETES
                            : ResourceClassifier.OPENSHIFT;

                    resources = generateResources(platformMode, resolvedImages);
                    writeResources(resources, resourceClassifier, generateRoute);
                    File resourceDir = new File(this.targetDir, resourceClassifier.getValue());
                    validateIfRequired(resourceDir, resourceClassifier);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate fabric8 descriptor", e);
        }
    }

    private void updateKindFilenameMappings() {
        if (mappings != null) {
            final Map<String, List<String>> mappingKindFilename = new HashMap<>();
            for (MappingConfig mappingConfig : this.mappings) {
                if (mappingConfig.isValid()) {
                    mappingKindFilename.put(mappingConfig.getKind(), Arrays.asList(mappingConfig.getFilenamesAsArray()));
                } else {
                    throw new IllegalArgumentException(String.format("Invalid mapping for Kind %s and Filename Types %s",
                        mappingConfig.getKind(), mappingConfig.getFilenameTypes()));
                }
            }
            KubernetesResourceUtil.updateKindFilenameMapper(mappingKindFilename);
        }
    }

    private void validateIfRequired(File resourceDir, ResourceClassifier classifier)
        throws MojoExecutionException, MojoFailureException {
        try {
            if (!skipResourceValidation) {
                new ResourceValidator(resourceDir, classifier, log).validate();
            }
        } catch (ConstraintViolationException e) {
            if (failOnValidationError) {
                log.error("[[R]]" + e.getMessage() + "[[R]]");
                log.error("[[R]]use \"mvn -Dfabric8.skipResourceValidation=true\" option to skip the validation[[R]]");
                throw new MojoFailureException("Failed to generate fabric8 descriptor");
            } else {
                log.warn("[[Y]]" + e.getMessage() + "[[Y]]");
            }
        } catch (Throwable e) {
            if (failOnValidationError) {
                throw new MojoExecutionException("Failed to validate resources", e);
            } else {
                log.warn("Failed to validate resources: %s", e.getMessage());
            }
        }
    }

    private void lateInit() {
        ClusterAccess clusterAccess = new ClusterAccess(getClusterConfiguration());
        runtimeMode = new ClusterAccess(getClusterConfiguration()).resolveRuntimeMode(runtimeMode, log);
        if (runtimeMode.equals(RuntimeMode.openshift)) {
            Properties properties = project.getProperties();
            if (!properties.contains(DOCKER_IMAGE_USER)) {
                String namespace = this.namespace != null && !this.namespace.isEmpty() ?
                        this.namespace: clusterAccess.getNamespace();
                log.info("Using docker image name of namespace: " + namespace);
                properties.setProperty(DOCKER_IMAGE_USER, namespace);
            }
            if (!properties.contains(RuntimeMode.FABRIC8_EFFECTIVE_PLATFORM_MODE)) {
                properties.setProperty(RuntimeMode.FABRIC8_EFFECTIVE_PLATFORM_MODE, runtimeMode.toString());
            }
        }
    }

    private KubernetesList generateResources(PlatformMode platformMode, List<ImageConfiguration> images)
        throws IOException, MojoExecutionException {

        if (namespace != null && !namespace.isEmpty()) {
            resources = new ResourceConfig.Builder(resources).withNamespace(namespace).build();
        }
        // Manager for calling enrichers.
        MavenEnricherContext.Builder ctxBuilder = new MavenEnricherContext.Builder()
                .project(project)
                .session(session)
                .config(extractEnricherConfig())
                .settings(settings)
                .properties(project.getProperties())
                .resources(resources)
                .images(resolvedImages)
                .log(log);

        EnricherManager enricherManager = new EnricherManager(resources, ctxBuilder.build(),
            MavenUtil.getCompileClasspathElementsIfRequested(project, useProjectClasspath));

        // Generate all resources from the main resource directory, configuration and create them accordingly
        KubernetesListBuilder builder = generateAppResources(platformMode, images, enricherManager);

        // Add resources found in subdirectories of resourceDir, with a certain profile
        // applied
        addProfiledResourcesFromSubirectories(platformMode, builder, realResourceDir, enricherManager);
        return builder.build();
    }

    private void addProfiledResourcesFromSubirectories(PlatformMode platformMode, KubernetesListBuilder builder, File resourceDir,
        EnricherManager enricherManager) throws IOException, MojoExecutionException {
        File[] profileDirs = resourceDir.listFiles((File pathname) -> pathname.isDirectory());
        if (profileDirs != null) {
            for (File profileDir : profileDirs) {
                Profile profile = ProfileUtil.findProfile(profileDir.getName(), resourceDir);
                if (profile == null) {
                    throw new MojoExecutionException(String.format("Invalid profile '%s' given as directory in %s. " +
                            "Please either define a profile of this name or move this directory away",
                        profileDir.getName(), resourceDir));
                }

                ProcessorConfig enricherConfig = profile.getEnricherConfig();
                File[] resourceFiles = KubernetesResourceUtil.listResourceFragments(profileDir);
                if (resourceFiles.length > 0) {
                    KubernetesListBuilder profileBuilder = readResourceFragments(platformMode, resourceFiles);
                    enricherManager.createDefaultResources(platformMode, enricherConfig, profileBuilder);
                    enricherManager.enrich(platformMode, enricherConfig, profileBuilder);
                    KubernetesList profileItems = profileBuilder.build();
                    for (HasMetadata item : profileItems.getItems()) {
                        builder.addToItems(item);
                    }
                }
            }
        }
    }

    private KubernetesListBuilder generateAppResources(PlatformMode platformMode, List<ImageConfiguration> images, EnricherManager enricherManager)
        throws IOException, MojoExecutionException {
        try {
            KubernetesListBuilder builder = processResourceFragments(platformMode);

            // Create default resources for app resources only
            enricherManager.createDefaultResources(platformMode, builder);

            // Enrich descriptors
            enricherManager.enrich(platformMode, builder);

            return builder;
        } catch (ConstraintViolationException e) {
            String message = ValidationUtil.createValidationMessage(e.getConstraintViolations());
            log.error("ConstraintViolationException: %s", message);
            throw new MojoExecutionException(message, e);
        }
    }

    private KubernetesListBuilder processResourceFragments(PlatformMode platformMode) throws IOException, MojoExecutionException {
        File[] resourceFiles = KubernetesResourceUtil.listResourceFragments(realResourceDir, resources !=null ? resources.getRemotes() : null, log);
        KubernetesListBuilder builder;

        // Add resource files found in the fabric8 directory
        if (resourceFiles != null && resourceFiles.length > 0) {
            log.info("using resource templates from %s", realResourceDir);
            builder = readResourceFragments(platformMode, resourceFiles);
        } else {
            builder = new KubernetesListBuilder();
        }
        return builder;
    }

    private KubernetesListBuilder readResourceFragments(PlatformMode platformMode, File[] resourceFiles) throws IOException, MojoExecutionException {
        KubernetesListBuilder builder;
        String defaultName = MavenUtil.createDefaultResourceName(project.getArtifactId());
        builder = KubernetesResourceUtil.readResourceFragmentsFrom(
            platformMode,
            KubernetesResourceUtil.DEFAULT_RESOURCE_VERSIONING,
            defaultName,
            mavenFilterFiles(resourceFiles, this.workDir));
        return builder;
    }

    private ProcessorConfig extractEnricherConfig() throws IOException {
        return ProfileUtil.blendProfileWithConfiguration(ProfileUtil.ENRICHER_CONFIG, profile, realResourceDir, enricher);
    }

    private ProcessorConfig extractGeneratorConfig() throws IOException {
        return ProfileUtil.blendProfileWithConfiguration(ProfileUtil.GENERATOR_CONFIG, profile, realResourceDir, generator);
    }

    // ==================================================================================

    private List<ImageConfiguration> getResolvedImages(List<ImageConfiguration> images, final KitLogger log)
        throws MojoExecutionException {
        List<ImageConfiguration> ret;
        ret = ConfigHelper.resolveImages(
            log,
            images,
            (ImageConfiguration image) -> imageConfigResolver.resolve(image, project, session),
            null,  // no filter on image name yet (TODO: Maybe add this, too ?)
                (List<ImageConfiguration> configs) -> {
                    try {
                        GeneratorContext ctx = new GeneratorContext.Builder()
                                .config(extractGeneratorConfig())
                                .project(project)
                                .runtimeMode(runtimeMode)
                                .logger(log)
                                .strategy(OpenShiftBuildStrategy.docker)
                                .useProjectClasspath(useProjectClasspath)
                                .build();
                        return GeneratorManager.generate(configs, ctx, true);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Cannot extract generator: " + e, e);
                    }
            });

        Date now = getBuildReferenceDate();
        storeReferenceDateInPluginContext(now);
        String minimalApiVersion = ConfigHelper.initAndValidate(ret, null /* no minimal api version */,
            new ImageNameFormatter(project, now), log);
        return ret;
    }

    private void storeReferenceDateInPluginContext(Date now) {
        Map<String, Object> pluginContext = getPluginContext();
        pluginContext.put(AbstractDockerMojo.CONTEXT_KEY_BUILD_TIMESTAMP, now);
    }

    // get a reference date
    private Date getBuildReferenceDate() throws MojoExecutionException {
        // Pick up an existing build date created by fabric8:build previously
        File tsFile = new File(project.getBuild().getDirectory(), DOCKER_BUILD_TIMESTAMP);
        if (!tsFile.exists()) {
            return new Date();
        }
        try {
            return EnvUtil.loadTimestamp(tsFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read timestamp from " + tsFile, e);
        }
    }

    private File[] mavenFilterFiles(File[] resourceFiles, File outDir) throws MojoExecutionException {
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new MojoExecutionException("Cannot create working dir " + outDir);
            }
        }
        File[] ret = new File[resourceFiles.length];
        int i = 0;
        for (File resource : resourceFiles) {
            File targetFile = new File(outDir, resource.getName());
            try {
                mavenFileFilter.copyFile(resource, targetFile, true,
                    project, null, false, "utf8", session);
                ret[i++] = targetFile;
            } catch (MavenFilteringException exp) {
                throw new MojoExecutionException(
                    String.format("Cannot filter %s to %s", resource, targetFile), exp);
            }
        }
        return ret;
    }

    private boolean hasFabric8Dir() {
        return realResourceDir.isDirectory();
    }

    private boolean isPomProject() {
        return "pom".equals(project.getPackaging());
    }

    protected void writeResources(KubernetesList resources, ResourceClassifier classifier, Boolean generateRoute)
        throws MojoExecutionException {
        // write kubernetes.yml / openshift.yml
        File resourceFileBase = new File(this.targetDir, classifier.getValue());

        File file =
            writeResourcesIndividualAndComposite(resources, resourceFileBase, this.resourceFileType, log, generateRoute);

        // Attach it to the Maven reactor so that it will also get deployed
        projectHelper.attachArtifact(project, this.resourceFileType.getArtifactType(), classifier.getValue(), file);
    }

    protected ClusterConfiguration getClusterConfiguration() {
        final ClusterConfiguration.Builder clusterConfigurationBuilder = new ClusterConfiguration.Builder(access);

        return clusterConfigurationBuilder.from(System.getProperties())
                .from(project.getProperties()).build();
    }
}
