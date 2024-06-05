# Define variables
$clusterName = "eksdemo1"
$region = "us-east-1"
$zones = "us-east-1a,us-east-1b"
$nodeGroupNamePublic = "eksdemo1-ng-public1"
$nodeGroupNamePrivate = "eksdemo1-ng"
$nodeType = "t3.medium"
$nodes = 2
$nodesMin = 2
$nodesMax = 4
$nodeVolumeSize = 20
$sshPublicKey = "kube-demo"
$serviceAccountName = "ebs-csi-controller-sa"
$namespace = "kube-system"
$policyArn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
$roleName = "AmazonEKS_EBS_CSI_DriverRole"
$addonName = "aws-ebs-csi-driver"

# Create Cluster
Write-Output "Creating EKS cluster..."
eksctl create cluster --name=$clusterName --region=$region --zones=$zones --without-nodegroup
if ($LASTEXITCODE -ne 0) {
    Write-Error "Cluster creation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

# Create Public Node Group
#Write-Output "Creating public node group..."
##eksctl create nodegroup --cluster=$clusterName --region=$region --name=$nodeGroupNamePublic --node-type=$nodeType --nodes=$nodes --nodes-min=$nodesMin --nodes-max=$nodesMax --node-volume-size=$nodeVolumeSize --ssh-access --ssh-public-key=$sshPublicKey --managed --asg-access --external-dns-access --full-ecr-access --appmesh-access --alb-ingress-access
#if ($LASTEXITCODE -ne 0) {
#    Write-Error "Public node group creation failed with exit code $LASTEXITCODE"
#    exit $LASTEXITCODE
##}

# Create Private Node Group
Write-Output "Creating private node group..."
eksctl create nodegroup --cluster=$clusterName --region=$region --name=$nodeGroupNamePrivate --node-type=$nodeType --nodes=$nodes --nodes-min=$nodesMin --nodes-max=$nodesMax --node-volume-size=$nodeVolumeSize --ssh-access --ssh-public-key=$sshPublicKey --managed --asg-access --external-dns-access --full-ecr-access --appmesh-access --alb-ingress-access --node-private-networking
if ($LASTEXITCODE -ne 0) {
    Write-Error "Private node group creation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

# Associate IAM OIDC Provider
Write-Output "Associating IAM OIDC provider..."
eksctl utils associate-iam-oidc-provider --region $region --cluster $clusterName --approve
if ($LASTEXITCODE -ne 0) {
    Write-Error "IAM OIDC provider association failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

# Create IAM Service Account
Write-Output "Creating IAM service account..."
eksctl create iamserviceaccount --region $region --name $serviceAccountName --namespace $namespace --cluster $clusterName --attach-policy-arn $policyArn --approve --role-only --role-name $roleName
if ($LASTEXITCODE -ne 0) {
    Write-Error "IAM service account creation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

# Create Addon
Write-Output "Creating addon..."
$accountId = (aws sts get-caller-identity --query Account --output text)
$roleArn = "arn:aws:iam::$accountId:role/$roleName"
eksctl create addon --name $addonName --cluster $clusterName --service-account-role-arn $roleArn --force
if ($LASTEXITCODE -ne 0) {
    Write-Error "Addon creation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Output "EKS cluster setup complete."

