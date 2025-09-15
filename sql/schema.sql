-- Neon Postgres schema for Spending Insights (Lite++)
create extension if not exists pgcrypto;

create table if not exists client (
  client_id uuid primary key default gen_random_uuid(),
  created_at timestamptz default now()
);

create table if not exists budget (
  id bigserial primary key,
  client_id uuid references client(client_id) on delete cascade,
  category text not null,
  monthly_limit numeric(10,2) not null,
  unique (client_id, category)
);

create table if not exists rule (
  id bigserial primary key,
  client_id uuid references client(client_id) on delete cascade,
  keyword text not null,
  category text not null
);
