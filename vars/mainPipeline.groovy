#!/usr/bin/groovy

import com.example.PipelineUtils

/**
   * Main Pipeline meant to take code from source all the way
   * to production in a "true pipeline"
   * @param gitRepoUrl URL to the DSM source code
   * @param microservice DSM name
   */
def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Instantiate variables used in pipeline
    //ocpAuthTokenCredentialId ="OCP_SA_CICD_JENKINS"

    tag = ""

    def pipelineUtils = new PipelineUtils()

    try {
        node('maven') {
            // Clean workspace before doing anything
            deleteDir()

            env.SKIP_TLS = 'true'

            stage('Checkout Source Code'){
                // Checkout source code
                def scmVars = checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'app-root']], submoduleCfg: [], userRemoteConfigs: [[url: config.gitRepoUrl]]])

                // Image tag will be the git commit, the best version option
                tag = scmVars.GIT_COMMIT.take(7)
                print "gitCommit: ${gitCommit}"

                // Checkout template files
                // Using tags here for the template files for versioning
                checkout([$class: 'GitSCM', branches: [[name: config.templateGitTag]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'templates']], submoduleCfg: [], userRemoteConfigs: [[url: config.templateGitRepoUrl]]])
            }

            pipelineUtils.unitTestAndPackageJar("app-root/pom.xml", "")

            pom = readMavenPom file: "app-root/pom.xml"
            pipelineUtils.processTemplateAndStartBuild("templates/build-template.yaml", "APPLICATION_NAME=${config.microservice} OUTPUT_IMAGE_TAG=${tag}",
                config.buildProject, config.microservice, "app-root/target/${pom.artifactId}-${pom.version}.jar")

            pipelineUtils.tagImage(config.buildProject, config.microservice, tag, config.testProject, config.microservice, tag)

            pipelineUtils.processTemplateAndDeploy("templates/deploy-service-route-template.yaml",
                "APPLICATION_NAME=${config.microservice} IMAGE_TAG=${tag}", config.testProject, config.microservice)

            // Testing stages go here

            input "A/B Deployment in PROD?"

            pipelineUtils.tagImage(config.test, config.microservice, tag, config.prodProject, config.microservice, tag)
            
            pipelineUtils.blueGreen("templates/deploy-service-route-template.yaml",
                "APPLICATION_NAME=${config.microservice} IMAGE_TAG=${tag}", config.prodProject, config.microservice)

        } // node

    } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
    }

}
