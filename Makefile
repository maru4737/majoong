.PHONY: test images deploy monitoring demo
test:
	mvn test
images:
	sudo docker build -t majoong/journey-service:0.1.0 .
	sudo docker build -t majoong/web:0.1.0 web
	sudo docker save majoong/journey-service:0.1.0 | sudo ctr -n k8s.io images import -
	sudo docker save majoong/web:0.1.0 | sudo ctr -n k8s.io images import -
deploy:
	kubectl apply -f deploy/k8s/majoong.yaml
	sudo install -m 644 deploy/k8s/majoong-proxy.conf /etc/nginx/snippets/majoong-proxy.conf
	sudo grep -q 'majoong-proxy.conf' /etc/nginx/sites-available/default || sudo sed -i '/include \/etc\/nginx\/snippets\/nearby500-location.conf;/a\        include /etc/nginx/snippets/majoong-proxy.conf;' /etc/nginx/sites-available/default
	sudo nginx -t && sudo systemctl reload nginx
monitoring:
	kubectl -n majoong-dev create configmap kibana-dashboard --from-file=dashboard.json=deploy/kibana/majoong-operations-dashboard.json --dry-run=client -o yaml | kubectl apply -f -
	kubectl apply -f deploy/k8s/elk.yaml
	@job_name=elastic-stack-bootstrap-$$(date +%s); kubectl -n majoong-dev create job --from=cronjob/elastic-stack-bootstrap $$job_name; kubectl -n majoong-dev wait --for=condition=complete job/$$job_name --timeout=120s
demo: images deploy
