# 마중구름

Java 21/Spring Boot 기반 배포입니다. `make demo`로 로컬 Kubernetes에 배포하고 `https://maru4737.duckdns.org/majoong/`에서 엽니다. DEMO의 ZZ101은 실제 운항편이 아닙니다.

LIVE 모드는 Kubernetes `icn-api-key` Secret의 `serviceKey`를 사용하며, 상세 도착 API의 `fid`, 코드셰어, 편명, 예정·변경시각, 터미널, 출구, 수하물수취대를 정규화합니다. 키는 Git, 이미지, 로그에 넣지 않습니다.

## 운영 로그

Vector가 `majoong-dev` 네임스페이스 로그를 Redis DB 1의 `logs:majoong:v1` 리스트에 JSON으로 기록합니다. Logstash가 이 리스트를 FIFO로 소비해 `majoong-logs-YYYY.MM.dd` Elasticsearch 인덱스로 적재하고, Kibana는 `https://maru4737.duckdns.org/majoong-monitor/`에서 제공합니다. 인덱스 수명주기 정책은 로그를 7일 후 삭제합니다.

Elastic Stack 배포 설정은 `deploy/k8s/elk.yaml`, 443 프록시와 Basic 인증 경로는 `deploy/k8s/majoong-proxy.conf`에 있습니다. 실제 인증키와 암호는 Git에 저장하지 않습니다.
