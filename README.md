# openshift-pipeline

Configure Jenkins with your OpenShift Cluster
http://v1.uncontained.io/playbooks/continuous_delivery/external-jenkins-integration.html

As a result of that effort you should have a jenkins service account that all your jenkins agents run as.

Create build, test and prod projects. Name as you please
```bash
oc new-project demo-build
oc new-project demo-test
oc new-project demo-prod
```

Give jenkins SA permission to those projects
```bash
oc policy add-role-to-user edit system:serviceaccount:cicd:jenkins -n demo-build
oc policy add-role-to-user edit system:serviceaccount:cicd:jenkins -n demo-test
oc policy add-role-to-user edit system:serviceaccount:cicd:jenkins -n demo-prod
```

Create a image inspector SA in test project with privileged scc
```bash
oc create sa image-inspector -n demo-test
oc adm policy add-scc-to-user privileged -z image-inspector -n demo-test
```

Now you can create a Jenkinsfile like this:
```
library identifier: 'openshift-pipeline@master', retriever: modernSCM([$class: 'GitSCMSource',
   remote: 'https://github.com/domenicbove/openshift-pipeline.git']) _

mainPipeline {
    gitRepoUrl = "https://github.com/domenicbove/simple-server.git"
    microservice = "simple-server"
    templateGitRepoUrl = "https://github.com/domenicbove/openshift-templates.git"
    templateGitTag = "master"
    ocpUrl = "master1.ocp.com"
    buildProject = "demo-build"
    testProject = "demo-test"
    prodProject = "demo-prod"
}
```

Note it is required that the above project steps completed for this pipeline to work. Also the mainPipeline is tied closely with my openshift-templates repo. There are many options of where to store openshift templates. I decided to put them in github
