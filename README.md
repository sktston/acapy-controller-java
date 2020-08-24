# Controller of aries-cloudagent-python (Java Spring boot)

### Steps to run
- Run Cloud Agents (Faber and Alice)
```
cd docker
docker-compose up
```
Faber agent opens 8020 port (endpoint) and 8021 port (admin). \
Check admin (swagger API) http://localhost:8021

Alice agent opens 8030 port (endpoint) and 8031 port (admin). \
Check admin http://localhost:8031

`docker-compose up --build` is fully recommended if you pull a repository recently.

- Run Faber Controller
```
./gradlew faber
```
Faber controller opens 8022 port. \
It receives webhook message from faber agent by POST http://localhost:8022/webhooks/topic/{topic}/ \
Also, It presents invitation by GET http://localhost:8022/invitation

- Run Alice Controller (Run with the faber running)
```
./gradlew alice
```
Alice controller opens 8032 port. \
It receives webhook message from alice agent by POST http://localhost:8032/webhooks/topic/{topic}/ \
When alice controller starts, it gets invitation from faber controller and proceeds connection, credential and proof(presentation) sequentially.

### Advanced user guide
- Revocation test
```
./gradlew faber_revoke
```
You can see below log at faber side when demo completes
```
[INFO ] [GlobalService.java]printProofResult(200) :   - Proof validation:false
```

- Change ledger

Open `docker/docker-compose.yml` \
Edit the value of `--genesis-transactions`

- Access faber from non-docker agent

Open file docker/docker-compose.yml. \
Edit `--endpoint http://host.docker.internal:8020` on command of acapy-faber-agent. \
It is the endpoint of invitation for receiving messages from other agents (e.g., alice). 

- Get invitation_url from faber

GET http://localhost:8022/invitation-url

### Demo flow (API-based description)
| Category | Faber Controller (topic, state) | Faber Agent | Alice Agent | Alice controller (topic, state) | Tails Server | can skip (bold enabled) |
|---|---|---|---|---|---|---|
| Schema/CredDef |  | POST /schemas |  |  |  |  |
|  |  | POST /credential-definitions |  |  |  |  |
|  |  | POST /revocation/create-registry |  |  |  |  |
|  |  | PATCH /revocation/registry/{rev_reg_id} |  |  |  |  |
|  |  | POST /revocation/registry/{rev_reg_id}/publish |  |  |  |  |
|  |  | GET /revocation/registry/{rev_reg_id}/tails-file |  |  |  |  |
|  |  |  |  |  | PUT /{revoc_reg_id} |  |
|  |  |  |  |  |  |  |
| Connection | connections, invitation | POST /connections/create-invitation |  |  |  |  |
|  |  |  | POST /connections/receive-invitation | connections, invitation |  |  |
|  | connections, request |  | POST /connections/{conn_id}/accept-invitation | connections, request |  | **--auto-accept-invites** |
|  | connections, response | POST /connections/{conn_id}/accept-request |  | connections, response |  | **--auto-accept-requests** |
|  | connections, active |  | POST /connections/{conn_id}/send-ping | connections, active |  | **--auto-ping-connection** |
|  |  |  |  |  |  |  |
| Credential | issue_credential, offer_sent | POST /issue-credential/send-offer |  | issue_credential, offer_received |  |  |
|  | issue_credential, request_received |  | POST /issue-credential/records/{cred_ex_id}/send-request | issue_credential, request_sent |  | --auto-respond-credential-offer |
|  | issue_credential, credential_issued | POST /issue-credential/records/{cred_ex_id}/issue |  | issue_credential, credential_received |  | **--auto-respond-credential-request** |
|  | issue_credential, credential_acked |  | POST /issue-credential/records/{cred_ex_id}/store | issue_credential, credential_acked |  | **--auto-store-credential** |
|  |  |  |  |  |  |  |
| Proof | present_proof, request_sent | /present-proof/send-request |  | present_proof, request_received |  |  |
|  |  |  | GET /present-proof/records/{pres_ex_id}/credentials |  |  |  |
|  | present_proof, presentation_received |  | POST /present-proof​/records​/{pres_ex_id}​/send-presentation | present_proof, presentation_sent |  | --auto-respond-presentation-request |
|  | present_proof, verified | POST /present-proof/records/{pres_ex_id}/verify-presentation |  | present_proof, presentation_acked |  | **--auto-verify-presentation** |
|  |  |  |  |  |  |  |
| (Optional) Revocation |  | POST /issue-credential/revoke |  |  |  |  |
|  |  |  |  |  |  |  |
| (Optional) Message | basicmessages, received |  | POST /connections/{conn_id}/send-message |  |  |  |
|  |  | POST /connections/{conn_id}/send-message |  | basicmessages, received |  | **--auto-respond-messages** |
|  |  |  |  |  |  |  |
