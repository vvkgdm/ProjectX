variable "aws_region" {
  description = "AWS region to deploy the Jenkins server"
  default     = "us-east-1"
}

variable "instance_type" {
  description = "EC2 instance type for the Jenkins server"
  default     = "t2.2xlarge"
}

variable "key_name" {
  description = "Name of the SSH key pair to use for the Jenkins server"
  default     = "kube-demo"
}

variable "disk_size_gb" {
  description = "Size of the EBS volume in GB"
  default     = 20
}

provider "aws" {
  region = var.aws_region
}

resource "aws_instance" "jenkins_server" {
  ami           = "ami-04b70fa74e45c3917" # Replace with the desired AMI ID for your region
  instance_type = var.instance_type
  key_name      = var.key_name

  root_block_device {
    volume_size = var.disk_size_gb
    volume_type = "gp2"
  }

  user_data = <<-EOF
              #!/bin/bash
              sudo apt-get update
              sudo apt install openjdk-17-jre-headless -y

              # Install Jenkins
              sudo wget -O /usr/share/keyrings/jenkins-keyring.asc https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key
              echo deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/ | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
              sudo apt-get update
              sudo apt-get install jenkins -y
              sudo systemctl start jenkins
              sudo systemctl enable jenkins

              # Install Docker
              sudo apt-get install -y ca-certificates curl
              sudo install -m 0755 -d /etc/apt/keyrings
              sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
              sudo chmod a+r /etc/apt/keyrings/docker.asc
              echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
              sudo apt-get update
              sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

              # Configure Docker to use insecure registry
              echo '{"insecure-registries" : ["localhost:8082"]}' | sudo tee /etc/docker/daemon.json
              sudo systemctl restart docker
              sudo chmod 666 /var/run/docker.sock 	
              # Run Docker containers
              docker run -d --name nexus -p 8081:8081 -p 8082:8082 -v nexus-data:/nexus-data sonatype/nexus3:latest
              docker run -d --name sonar -p 9000:9000 sonarqube:lts-community

              # Wait for Nexus to start
              echo "Waiting for Nexus to start..."
              until $(curl --output /dev/null --silent --head --fail http://localhost:8081); do
                printf '.'
                sleep 5
              done

              # Wait a bit more to ensure Nexus is fully initialized
              sleep 30

              # Get the admin password
              NEXUS_PASSWORD=$(docker exec nexus cat /nexus-data/admin.password)

              # Create Docker hosted repository in Nexus
              echo "Creating Docker hosted repository in Nexus..."
              curl -v -u admin:$${NEXUS_PASSWORD} -X POST 'http://localhost:8081/service/rest/v1/repositories/docker/hosted' \
              -H 'Content-Type: application/json' \
              -d '{
                "name": "docker-private",
                "online": true,
                "storage": {
                  "blobStoreName": "default",
                  "strictContentTypeValidation": true,
                  "writePolicy": "allow"
                },
                "docker": {
                  "v1Enabled": true,
                  "forceBasicAuth": true,
                  "httpPort": 8082
                }
              }'

              # Log the result
              if [ $? -eq 0 ]; then
                  echo "Docker repository created successfully"
              else
                  echo "Failed to create Docker repository"
              fi

              # Enable Docker Bearer Token Realm
              curl -v -u admin:$${NEXUS_PASSWORD} -X PUT 'http://localhost:8081/service/rest/v1/security/realms/active' \
              -H 'Content-Type: application/json' \
              -d '["NexusAuthenticatingRealm","DockerToken"]'

              # Create a new task to rebuild HTTP Service
              curl -v -u admin:$${NEXUS_PASSWORD} -X POST 'http://localhost:8081/service/rest/v1/tasks' \
              -H 'Content-Type: application/json' \
              -d '{
                "name": "Rebuild HTTP Service",
                "typeId": "repair-http-service",
                "enabled": true,
                "alertEmail": "",
                "schedule": {
                  "type": "manual"
                }
              }'

              echo "Nexus setup completed"
              EOF

  tags = {
    Name = "Jenkins Server"
  }

  vpc_security_group_ids = [aws_security_group.jenkins_security_group.id]
  depends_on             = [aws_security_group.jenkins_security_group]
}

resource "aws_security_group" "jenkins_security_group" {
  name        = "jenkins-security-group"
  description = "Security group for Jenkins server"

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 8081
    to_port     = 8081
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 8082
    to_port     = 8082
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 9000
    to_port     = 9000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "Jenkins Security Group"
  }
}

output "jenkins_server_public_ip" {
  value = aws_instance.jenkins_server.public_ip
}