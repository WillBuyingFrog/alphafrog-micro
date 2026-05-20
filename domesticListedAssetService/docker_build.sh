mvn -f ../pom.xml -pl domesticListedAssetService -am clean package -DskipTests
docker build -t alphafrog-micro-domestic-listed-asset-service:latest .
