-- 쇼핑몰 데이터베이스 스키마

CREATE DATABASE IF NOT EXISTS shop_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_db;

-- 사용자
CREATE TABLE users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    phone       VARCHAR(20),
    address     VARCHAR(500),
    grade       ENUM('BRONZE','SILVER','GOLD','VIP') NOT NULL DEFAULT 'BRONZE',
    point_balance INT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 카테고리
CREATE TABLE categories (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    parent_id   INT DEFAULT NULL,
    FOREIGN KEY (parent_id) REFERENCES categories(id)
);

-- 상품
CREATE TABLE products (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id  INT NOT NULL,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    price        INT NOT NULL,
    image_url    VARCHAR(500),
    is_active    TINYINT(1) NOT NULL DEFAULT 1,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (category_id) REFERENCES categories(id)
);

-- 재고
CREATE TABLE inventory (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id  BIGINT NOT NULL UNIQUE,
    quantity    INT NOT NULL DEFAULT 0,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id) REFERENCES products(id)
);

-- 주문
CREATE TABLE orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    status          ENUM('PENDING','PAID','PREPARING','SHIPPED','DELIVERED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    total_amount    INT NOT NULL,
    discount_amount INT NOT NULL DEFAULT 0,
    point_used      INT NOT NULL DEFAULT 0,
    point_earned    INT NOT NULL DEFAULT 0,
    recipient_name  VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(20) NOT NULL,
    address         VARCHAR(500) NOT NULL,
    memo            VARCHAR(255),
    ordered_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 주문 상품
CREATE TABLE order_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    unit_price  INT NOT NULL,
    quantity    INT NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (product_id) REFERENCES products(id)
);

-- 배송
CREATE TABLE shipping (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT NOT NULL UNIQUE,
    courier         VARCHAR(50),
    tracking_number VARCHAR(100),
    status          ENUM('READY','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','FAILED') NOT NULL DEFAULT 'READY',
    shipped_at      DATETIME,
    delivered_at    DATETIME,
    FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- 적립금 내역
CREATE TABLE point_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    order_id    BIGINT,
    type        ENUM('EARN','USE','EXPIRE','ADMIN') NOT NULL,
    amount      INT NOT NULL,
    balance     INT NOT NULL,
    description VARCHAR(255),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
