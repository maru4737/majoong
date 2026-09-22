.PHONY: test images deploy demo
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
demo: images deploy
