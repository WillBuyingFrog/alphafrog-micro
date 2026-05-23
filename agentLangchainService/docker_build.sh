USE_PROXY=${USE_PROXY:-1}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Ensure Spring Boot repackage produces an executable fat jar before COPY into the image.
mvn -pl agentLangchainService -am package -DskipTests -q

JAR="agentLangchainService/target/agentLangchainService-1.0.0-SNAPSHOT.jar"
if ! unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q 'Main-Class: org.springframework.boot.loader.launch.JarLauncher'; then
  echo "Expected executable Spring Boot jar at $JAR (missing JarLauncher Main-Class)." >&2
  echo "Run: mvn -pl agentLangchainService -am package -DskipTests" >&2
  exit 1
fi

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 all_proxy=socks5://127.0.0.1:7890
  PROXY_ARGS="--build-arg http_proxy=$http_proxy --build-arg https_proxy=$https_proxy"
else
  unset https_proxy http_proxy all_proxy
  PROXY_ARGS=""
fi

docker build $PROXY_ARGS -t alphafrog-micro-agent-langchain-service:latest ./agentLangchainService
