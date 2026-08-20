#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for BankTO Java payment/ledger work.
# Installs JDK 17 and warms Maven Wrapper dependencies. Does not start
# application services, databases, Docker stacks, or Kubernetes.
set -euo pipefail

JAVA_HOME_DIR="/usr/lib/jvm/java-17-openjdk-amd64"

if ! command -v sudo >/dev/null 2>&1; then
  echo "sudo is required to install JDK 17" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

if [ ! -x "${JAVA_HOME_DIR}/bin/java" ]; then
  sudo apt-get update -y
  sudo apt-get install -y --no-install-recommends openjdk-17-jdk
fi

if command -v update-java-alternatives >/dev/null 2>&1; then
  sudo update-java-alternatives --set java-1.17.0-openjdk-amd64 >/dev/null 2>&1 || true
fi
sudo update-alternatives --set java "${JAVA_HOME_DIR}/bin/java"
sudo update-alternatives --set javac "${JAVA_HOME_DIR}/bin/javac"

# Maven Wrapper sources /etc/mavenrc, so persist JAVA_HOME for ./mvnw.
sudo tee /etc/mavenrc >/dev/null <<'EOF'
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
EOF

if ! grep -q '^JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64$' /etc/environment 2>/dev/null; then
  echo 'JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' | sudo tee -a /etc/environment >/dev/null
fi

sudo tee /etc/profile.d/java17.sh >/dev/null <<'EOF'
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
EOF
sudo chmod 644 /etc/profile.d/java17.sh

export JAVA_HOME="${JAVA_HOME_DIR}"
export PATH="${JAVA_HOME}/bin:${PATH}"

java -version
chmod +x ./mvnw
./mvnw -pl src/ledger/ledgerwriter,src/ledgermonolith -am -DskipTests test-compile
