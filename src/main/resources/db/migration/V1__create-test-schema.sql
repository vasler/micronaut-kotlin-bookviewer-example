
CREATE SEQUENCE bookviewer.user_pk_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;

CREATE TABLE bookviewer.user (
	id numeric NOT NULL,
	username varchar NOT NULL,
	status varchar NOT NULL,
	update_ts timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
	creation_ts timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
	CONSTRAINT user_pk PRIMARY KEY (id),
	CONSTRAINT user_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
	CONSTRAINT user_un UNIQUE (username)
);
CREATE UNIQUE INDEX user_id_idx ON bookviewer.user (id);
CREATE UNIQUE INDEX user_username_idx ON bookviewer.user (username);

-- Column comments
COMMENT ON COLUMN bookviewer."user".id IS 'Primary key';




CREATE SEQUENCE bookviewer.book_pk_seq
	INCREMENT BY 1
	MINVALUE 1
	MAXVALUE 9223372036854775807
	START 1
	CACHE 1
	NO CYCLE;

CREATE TABLE bookviewer.book (
	id numeric NOT NULL,
	user_id numeric NOT NULL,
	isbn varchar NOT NULL,
	storage_key varchar NOT NULL,
	page_count integer  NOT NULL,
	process_count integer NOT NULL DEFAULT 0,
	status varchar NOT NULL,
	update_ts timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
	creation_ts timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
	CONSTRAINT book_pk PRIMARY KEY (id),
	CONSTRAINT book_status_check CHECK (status IN ('UNPROCESSED', 'PROCESSED', 'FAILED')),
	CONSTRAINT book_fk FOREIGN KEY (user_id) REFERENCES bookviewer.user(id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX book_id_idx ON bookviewer.book (id);
CREATE INDEX book_user_id_idx ON bookviewer.book (user_id);
CREATE INDEX book_isbn_idx ON bookviewer.book (isbn);
CREATE INDEX book_status_idx ON bookviewer.book (status);
CREATE INDEX book_creation_ts_idx ON bookviewer.book (creation_ts);
