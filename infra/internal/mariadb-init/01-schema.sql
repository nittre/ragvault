-- 게시판 테스트 DB 스키마
-- MariaDB 10.x 기준

CREATE DATABASE IF NOT EXISTS board_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE board_db;

-- 사용자
CREATE TABLE users (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  username    VARCHAR(50)   NOT NULL UNIQUE,
  email       VARCHAR(100)  NOT NULL UNIQUE,
  password    VARCHAR(255)  NOT NULL,
  nickname    VARCHAR(50)   NOT NULL,
  profile_img VARCHAR(500)  NULL,
  role        ENUM('ADMIN','MEMBER') NOT NULL DEFAULT 'MEMBER',
  is_active   TINYINT(1)    NOT NULL DEFAULT 1,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

-- 메뉴 (게시판 카테고리)
CREATE TABLE menus (
  id          INT           NOT NULL AUTO_INCREMENT,
  parent_id   INT           NULL,
  name        VARCHAR(100)  NOT NULL,
  slug        VARCHAR(100)  NOT NULL UNIQUE,
  description VARCHAR(500)  NULL,
  sort_order  INT           NOT NULL DEFAULT 0,
  is_visible  TINYINT(1)    NOT NULL DEFAULT 1,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (parent_id) REFERENCES menus(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- 게시글
CREATE TABLE posts (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  menu_id     INT           NOT NULL,
  author_id   BIGINT        NOT NULL,
  title       VARCHAR(300)  NOT NULL,
  content     LONGTEXT      NOT NULL,
  view_count  INT           NOT NULL DEFAULT 0,
  is_pinned   TINYINT(1)    NOT NULL DEFAULT 0,
  is_deleted  TINYINT(1)    NOT NULL DEFAULT 0,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (menu_id)   REFERENCES menus(id),
  FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- 댓글 (parent_id NULL → 댓글, NOT NULL → 대댓글)
CREATE TABLE comments (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  post_id     BIGINT        NOT NULL,
  author_id   BIGINT        NOT NULL,
  parent_id   BIGINT        NULL,
  content     TEXT          NOT NULL,
  is_deleted  TINYINT(1)    NOT NULL DEFAULT 0,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (post_id)   REFERENCES posts(id)    ON DELETE CASCADE,
  FOREIGN KEY (author_id) REFERENCES users(id),
  FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 좋아요 (게시글 / 댓글 공용, target_type 으로 구분)
CREATE TABLE likes (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  user_id     BIGINT        NOT NULL,
  target_type ENUM('POST','COMMENT') NOT NULL,
  target_id   BIGINT        NOT NULL,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_like (user_id, target_type, target_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- binlog 모니터링 권한 (binlog 동기화용)
GRANT BINLOG MONITOR ON *.* TO 'boarduser'@'%';
FLUSH PRIVILEGES;
