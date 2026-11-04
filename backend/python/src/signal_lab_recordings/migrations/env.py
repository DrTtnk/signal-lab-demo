from alembic import context
from sqlalchemy.engine import Connection

connection = context.config.attributes["connection"]
assert isinstance(connection, Connection)
context.configure(connection=connection, version_table_schema="recordings")
with context.begin_transaction():
    context.run_migrations()
