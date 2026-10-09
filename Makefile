.PHONY: up down api-test web-test e2e rush audit dev

up:          ## PostgreSQL, two API instances, nginx + the web app on :8090
	docker compose up -d --build --wait

down:
	docker compose down

api-test:    ## 24 integration tests on real PostgreSQL (Testcontainers) + coverage gate
	cd api && ./mvnw -B verify

web-test:    ## ESLint, tsc, Vitest with coverage
	cd web && npm ci && npm run lint && npm run typecheck && npm run coverage

e2e: up      ## Playwright: two buyers on different instances, a seat race, payment, a restart
	cd web && npx playwright test

rush: up     ## k6: 200 buyers for 60 s at one 1,000-seat event, then the double-booking audit
	docker run --rm --network event-ticketing-platform_default -v "$(PWD)/load:/load" \
		-e BASE=http://web -e VUS=$${VUS:-200} -e DURATION=$${DURATION:-60s} grafana/k6 run /load/booking-rush.js
	$(MAKE) audit

audit:       ## was any seat sold twice? (latest event)
	docker compose exec -T postgres psql -U ticketing -d ticketing -f - < load/audit.sql

dev:         ## API on :8080 against the compose database; Vite on :5173 (proxy to :8090 by default)
	@echo "cd api && ./mvnw spring-boot:run   |   cd web && npm run dev"
