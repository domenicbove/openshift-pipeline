# openshift-pipeline

Configure jenkins setup

Create demo-build, demo-test, demo-prod project
oc new-project demo-build
oc new-project demo-test
oc new-project demo-prod

Give jenkins SA permission to those projects

oc policy add-role-to-user view system:serviceaccount:cicd:jenkins -n demo-build
oc policy add-role-to-user view system:serviceaccount:cicd:jenkins -n demo-test
oc policy add-role-to-user view system:serviceaccount:cicd:jenkins -n demo-prod


Create jenkins pipeline
