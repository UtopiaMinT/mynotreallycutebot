-- general guild stuff

create table if not exists war_log (
    id int auto_increment,
    attacker varchar(40) not null,
    defender varchar(40) not null,
    attacker_terr_count int,
    defender_terr_count int,
    start_time bigint not null,
    end_time bigint not null,
    verdict enum('started', 'won', 'lost') not null default 'started',
    server varchar(8),
    terr_name varchar(40),
    total int not null default 0,
    won int not null default 0,
    primary key(id),
    index(attacker),
    index(start_time)
);

create table if not exists player_war_log (
    id int auto_increment,
    war_id int not null,
    ign varchar(16) not null,
    uuid varchar(32) not null,
    guild varchar(40) not null,
    total int not null default 0,
    won int not null default 0,
    primary key(id),
    index(uuid, guild)
);

create table if not exists guild_xp (
    guild varchar(40),
    xp bigint,
    level int,
    timestamp bigint,
    primary key(timestamp, guild)
);

-- lxa stuff
create table if not exists lxa_contribution (
    ign varchar(40),
    uuid varchar(32),
    xp bigint,
    emeralds int,
    joined_time bigint,
    left_time bigint,
    primary key (uuid)
);

-- stalking stuff
create table if not exists player_session (
    ign varchar(40),
    uuid varchar(32),
    session_start bigint,
    session_end bigint,
    total_playtime int,
    primary key (uuid, session_start),
    index(uuid)
);

-- cache thing
create table if not exists timestamps (
    tag varchar(16),
    timestamp bigint not null,
    primary key (tag)
);

create table if not exists ign_cache (
    uuid varchar(32),
    ign varchar(40),
    time bigint,
    primary key (uuid)
);

create table if not exists guild_tag (
    guild varchar(40),
    tag varchar(4),
    index(guild),
    index(tag)
);