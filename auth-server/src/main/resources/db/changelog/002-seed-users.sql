--liquibase formatted sql

--changeset junie:002-seed-user-user
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM users WHERE username = 'user'
INSERT INTO users (username, password, email, first_name, last_name, enabled)
VALUES ('user', '{bcrypt}$2a$10$a4bGARTQ7NH.qZB8ZOnmB.lBU8dVflV0xRU50tk1xLJLvD4DqowCa',
        'user@example.com', 'Regular', 'User', true);
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'user'), 'ROLE_USER');
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'user'), 'USER');

--changeset junie:002-seed-user-admin
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM users WHERE username = 'admin'
INSERT INTO users (username, password, email, first_name, last_name, enabled)
VALUES ('admin', '{bcrypt}$2a$10$7WWkj4/Pda.Te3KL85hMHuCt4S5oaEVA3UkNFXuyy7YI.Pm90eDly',
        'admin@example.com', 'System', 'Admin', true);
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'admin'), 'ROLE_ADMIN');
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'admin'), 'ADMIN');

--changeset junie:002-seed-user-manager
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM users WHERE username = 'manager'
INSERT INTO users (username, password, email, first_name, last_name, enabled)
VALUES ('manager', '{bcrypt}$2a$10$TGcpfcJCCsGA2k10cgmE8epwmor/jf5ekZaK6CzaN.UkdLvcWMX2C',
        'manager@example.com', 'Team', 'Manager', true);
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'manager'), 'ROLE_USER');
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'manager'), 'USER');
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'manager'), 'ROLE_ADMIN');
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'manager'), 'ADMIN');
INSERT INTO authorities (user_id, authority)
VALUES ((SELECT id FROM users WHERE username = 'manager'), 'MANAGER');
