# DBagnets

Agent loop that generates database initialization scripts using Claude.

## Usage

```bash
cd src

# PostgreSQL - movie management system
python main.py \
  --db-type relational \
  --db-name postgresql \
  --db-version 13 \
  --idea "movie management system with actors, directors, genres and reviews" \
  --depth 4 \
  --output init.sql

# MySQL - e-commerce platform
python main.py \
  --db-type relational \
  --db-name mysql \
  --db-version 8.0 \
  --idea "e-commerce platform with products, orders, customers and shipping" \
  --depth 4 \
  --output shop.sql

# Neo4j - social network
python main.py \
  --db-type graph \
  --db-name neo4j \
  --db-version 5.0 \
  --idea "social network for developers with projects, skills and companies" \
  --depth 3 \
  --output social.cypher

# Milvus - image search
python main.py \
  --db-type vector \
  --db-name milvus \
  --db-version 2.3 \
  --idea "image similarity search engine with tags and categories" \
  --depth 2 \
  --output images.py

# With debug logging
python main.py \
  --db-type relational \
  --db-name postgresql \
  --db-version 16 \
  --idea "hospital management with patients, doctors, appointments and prescriptions" \
  --depth 5 \
  --verbose

# Sequential validation (slower but easier to read logs)
python main.py \
  --db-type relational \
  --db-name postgresql \
  --db-version 15 \
  --idea "library management system" \
  --depth 3 \
  --sequential

# Custom model and iteration limit
python main.py \
  --db-type relational \
  --db-name postgresql \
  --db-version 14 \
  --idea "university course registration system" \
  --depth 4 \
  --model claude-sonnet-4-6 \
  --max-iterations 5

# Pipe script to file, logs go to stderr
python main.py \
  --db-type relational \
  --db-name postgresql \
  --db-version 13 \
  --idea "restaurant reservation system" \
  --depth 3 \
  > restaurant.sql
```

## Options

```
--db-type        relational | graph | vector | document | key_value | time_series
--db-name        Engine name (postgresql, mysql, neo4j, milvus, etc.)
--db-version     Engine version (13, 8.0, 5.0, etc.)
--idea           What the database is for (free text)
--depth          Relationship depth (number of FK levels)
--output         Save script to file (default: stdout)
--max-iterations Max agent loop iterations (default: 10)
--model          Claude model (default: claude-sonnet-4-6)
--region         Vertex AI region (default: global)
--project-id     GCP project ID
--sequential     Run validators one by one instead of in parallel
-v, --verbose    Debug logging (prompts, token counts)
```