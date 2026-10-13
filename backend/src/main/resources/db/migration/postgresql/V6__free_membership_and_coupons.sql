-- Free membership: how many months a business that signs up by itself uses the product before it
-- pays (0 switches it off). Coupons: codes the product admin hands out for a discount on the plan.
insert into platform_setting (setting_key, setting_value) values ('signup.freeMonths', '0');

create table coupon (
    code varchar(20) not null,
    percent_off integer not null,
    max_uses integer not null,
    used integer not null,
    valid_until date,
    active boolean not null,
    note varchar(200),
    created_at timestamp(6) with time zone not null,
    created_by varchar(255),
    primary key (code)
);

alter table signup_request add column coupon_code varchar(20);
alter table signup_request add column discount numeric(12, 2) default 0 not null;
alter table signup_request add column free_months integer default 0 not null;
