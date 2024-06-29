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
    volume_type = "gp2"  # Use General Purpose SSD (gp2) volume type
  }

  user_data = <<-EOF
    #!/bin/bash
    sudo apt-get update
    sudo apt install openjdk-17-jre-headless -y
    sudo wget -O /usr/share/keyrings/jenkins-keyring.asc https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key
    echo deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/ | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
    sudo apt-get update
    sudo apt-get install jenkins -y
    sudo systemctl start jenkins
    sudo systemctl enable jenkins
    # Update package manager repositories
    sudo apt-get update

    # Install necessary dependencies
    sudo apt-get install -y ca-certificates curl

    # Create directory for Docker GPG key
    sudo install -m 0755 -d /etc/apt/keyrings

    # Download Docker's GPG key
    sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc

    # Ensure proper permissions for the key
    sudo chmod a+r /etc/apt/keyrings/docker.asc

    # Add Docker repository to Apt sources
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

    # Update package manager repositories
    sudo apt-get update
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin 

    # Run Docker containers
    docker run -d --name nexus -p 8081:8081 sonatype/nexus3:latest
    docker run -d --name sonar -p 9000:9000 sonarqube:lts-community
  EOF

  tags = {
    Name = "Jenkins Server"
  }

  vpc_security_group_ids = [aws_security_group.jenkins_security_group.id]

  depends_on = [aws_security_group.jenkins_security_group]
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
