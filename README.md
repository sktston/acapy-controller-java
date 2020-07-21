# Controller of aries-cloudagent-python (Java Spring boot)

### This repository is currently in development.

### Steps to run
- Run Cloud Agents (Faber and Alice)
```
cd docker
docker-compose up
```
- Run Faber Controller
```
./gradlew faber
```
- Run Alice Controller (Run with the faber running)
```
./gradlew alice
```