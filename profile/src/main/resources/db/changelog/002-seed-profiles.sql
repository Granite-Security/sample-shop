--liquibase formatted sql

--changeset adrian:002-seed-profile-user
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM user_profile WHERE username = 'user'
INSERT INTO user_profile (username, email, first_name, last_name)
VALUES ('user', 'user@example.com', 'Regular', 'User');

--changeset adrian:002-seed-profile-admin
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM user_profile WHERE username = 'admin'
INSERT INTO user_profile (username, email, first_name, last_name)
VALUES ('admin', 'admin@example.com', 'System', 'Admin');

--changeset adrian:002-seed-profile-manager
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM user_profile WHERE username = 'manager'
INSERT INTO user_profile (username, email, first_name, last_name)
VALUES ('manager', 'manager@example.com', 'Team', 'Manager');
