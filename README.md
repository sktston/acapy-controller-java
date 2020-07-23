# Controller of aries-cloudagent-python (Java Spring boot)

### This repository is currently in development.

### Steps to run
- Run Cloud Agents (Faber and Alice)
```
cd docker
docker-compose up
```
Faber uses 8020 port (endpoint) and 8021 port (admin). \
Check admin (swagger API) http://localhost:8021

Alice uses 8030 port (endpoint) and 8031 port (admin). \
Check admin http://localhost:8031

- Run Faber Controller
```
./gradlew faber
```
- Run Alice Controller (Run with the faber running)
```
./gradlew alice
```