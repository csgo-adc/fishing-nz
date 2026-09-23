# CatchCheck API

FastAPI service shared by the CatchCheck web, iOS, and Android clients.

## Local development

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
cp .env.example .env
uvicorn app.main:app --reload
```

The API is available at `http://localhost:8000`, interactive documentation at `/docs`, and the health check at `/api/v1/health`.

## Checks

```bash
ruff check .
pytest
```
