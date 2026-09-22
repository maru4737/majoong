# 마중구름

Java 21/Spring Boot 기반 배포입니다. `make demo`로 로컬 Kubernetes에 배포하고 `https://maru4737.duckdns.org/majoong/`에서 엽니다. DEMO의 ZZ101은 실제 운항편이 아닙니다.

LIVE 모드는 Kubernetes `icn-api-key` Secret의 `serviceKey`를 사용하며, 상세 도착 API의 `fid`, 코드셰어, 편명, 예정·변경시각, 터미널, 출구, 수하물수취대를 정규화합니다. 키는 Git, 이미지, 로그에 넣지 않습니다.
