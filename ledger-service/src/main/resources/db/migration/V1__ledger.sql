-- Double-entry ledger for futures trading.
--
-- Two kinds of asset move through the ledger: dollars ('USD', in integer
-- cents) and futures contracts (asset = the contract symbol, e.g. 'NGF27',
-- in whole contracts). Every journal entry must net to zero per asset, which
-- a deferred constraint trigger checks at commit time, so no code path -
-- including a bug or a manual psql session - can leave the books unbalanced.

create table instrument (
    symbol          text primary key,                      -- NGF27 = NG, January 2027
    root            text not null,                         -- NG, CL
    contract_month  date not null check (extract(day from contract_month) = 1),
    multiplier      bigint not null check (multiplier > 0), -- units per contract: 10,000 MMBtu
    tick_size       numeric(12, 6) not null check (tick_size > 0),
    last_trade_date date not null,
    unique (root, contract_month)
);

create table account (
    id    bigserial primary key,
    code  text not null unique,     -- e.g. BOOK:NG-MACRO:CASH
    book  text,                     -- null for house accounts
    kind  text not null check (kind in ('BOOK_CASH', 'BOOK_POSITION', 'CLEARING_CASH',
                                         'CLEARING_POSITION', 'FEES', 'FUNDING'))
);

-- House accounts every entry posts against.
insert into account (code, book, kind) values
    ('CLEARING:CASH', null, 'CLEARING_CASH'),        -- the clearing broker: pays/collects variation margin
    ('CLEARING:POSITION', null, 'CLEARING_POSITION'), -- the other side of every contract
    ('FEES', null, 'FEES'),                          -- commissions and exchange fees collected
    ('FUNDING', null, 'FUNDING');                    -- where cash deposits come from

create table fill (
    id           bigserial primary key,
    book         text not null,
    symbol       text not null references instrument (symbol),
    side         text not null check (side in ('BUY', 'SELL')),
    quantity     integer not null check (quantity > 0),
    price        numeric(18, 6) not null check (price > 0),
    fee_cents    bigint not null check (fee_cents >= 0),
    trade_date   date not null,
    executed_at  timestamptz not null,
    created_at   timestamptz not null default now()
);
create index fill_book_date on fill (book, trade_date);

create table journal_entry (
    id             bigserial primary key,
    entry_type     text not null check (entry_type in ('FILL', 'VARIATION_MARGIN', 'DEPOSIT')),
    business_date  date not null,
    description    text not null,
    fill_id        bigint references fill (id),
    created_at     timestamptz not null default now()
);

create table posting (
    id          bigserial primary key,
    entry_id    bigint not null references journal_entry (id),
    account_id  bigint not null references account (id),
    asset       text not null,                  -- 'USD' or a contract symbol
    amount      bigint not null check (amount <> 0)  -- change in the account's holding: cents or contracts
);
create index posting_entry on posting (entry_id);
create index posting_account_asset on posting (account_id, asset);

-- Running balance per book account and asset, kept in step with the postings
-- in the same transaction, so reading a book's cash or position is one row.
-- House accounts (clearing, fees, funding) have no rows here: they take part in
-- every transaction, and a single hot row would serialize the whole system.
-- Their balances are summed from posting when needed.
create table balance (
    account_id  bigint not null references account (id),
    asset       text not null,
    amount      bigint not null default 0,
    primary key (account_id, asset)
);

create function check_entry_balanced() returns trigger language plpgsql as $$
declare
    bad record;
begin
    select asset, sum(amount) as total into bad
      from posting where entry_id = new.entry_id
     group by asset having sum(amount) <> 0
     limit 1;
    if found then
        raise exception 'journal entry % does not balance: % nets to %', new.entry_id, bad.asset, bad.total;
    end if;
    return null;
end $$;

-- Deferred: runs at COMMIT, after all of the entry's postings are in.
create constraint trigger posting_balanced
    after insert on posting
    deferrable initially deferred
    for each row execute function check_entry_balanced();

-- Posted entries are history: corrections are new entries, never edits.
create function reject_change() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only', tg_table_name;
end $$;
create trigger posting_append_only before update or delete on posting
    for each row execute function reject_change();
create trigger journal_entry_append_only before update or delete on journal_entry
    for each row execute function reject_change();

-- Idempotency-Key handling for POST /api/fills: the first request with a key
-- stores its response; retries with the same key get the same response back.
create table idempotency_key (
    key            text primary key,
    request_hash   text not null,
    response_code  integer,
    response_body  text,
    created_at     timestamptz not null default now()
);

-- Daily settlement prices (from the exchange; here, EIA's published settles).
create table settlement_price (
    symbol         text not null references instrument (symbol),
    business_date  date not null,
    price          numeric(18, 6) not null check (price > 0),
    primary key (symbol, business_date)
);

-- Daily log returns of risk factors for historical VaR, e.g. 'NG:C1' = NG
-- nearest contract, roll-adjusted (see research/commodity_research/returns.py).
create table factor_return (
    factor         text not null,
    business_date  date not null,
    log_return     double precision not null,
    primary key (factor, business_date)
);

-- One variation-margin run per book and day; makes POST /api/eod idempotent.
create table eod_run (
    book           text not null,
    business_date  date not null,
    entry_id       bigint references journal_entry (id),  -- null when there was nothing to settle
    created_at     timestamptz not null default now(),
    primary key (book, business_date)
);
