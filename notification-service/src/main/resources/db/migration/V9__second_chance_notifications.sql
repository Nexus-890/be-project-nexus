CREATE TABLE notification_offer_outcomes (
    offer_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    PRIMARY KEY (offer_id,event_type)
);
