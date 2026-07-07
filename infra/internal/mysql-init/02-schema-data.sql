-- customer_sample 테스트용 스키마 + 샘플 데이터
-- 챗 서비스(RAG) binlog 동기화 테스트에 사용하는 고객 서비스 예시 DB

USE customer_sample;

-- 고객
CREATE TABLE customers (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    phone       VARCHAR(20),
    address     VARCHAR(500),
    grade       ENUM('BRONZE','SILVER','GOLD','VIP') NOT NULL DEFAULT 'BRONZE',
    joined_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 주문 내역
CREATE TABLE orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id  BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    amount       INT NOT NULL,
    status       ENUM('PENDING','PAID','SHIPPED','DELIVERED','CANCELLED') NOT NULL DEFAULT 'PAID',
    ordered_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB;

-- 고객 문의 (CS 챗봇 RAG 테스트용)
CREATE TABLE inquiries (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id  BIGINT NOT NULL,
    category     ENUM('배송','환불','상품문의','계정','기타') NOT NULL,
    title        VARCHAR(300) NOT NULL,
    content      TEXT NOT NULL,
    status       ENUM('OPEN','IN_PROGRESS','RESOLVED','CLOSED') NOT NULL DEFAULT 'OPEN',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB;

INSERT INTO customers (name, email, phone, address, grade, joined_at) VALUES
('김민준', 'minjun.kim@example.com', '010-1111-2222', '서울시 강남구 테헤란로 123', 'GOLD', '2023-01-15 09:00:00'),
('이서연', 'seoyeon.lee@example.com', '010-2222-3333', '서울시 마포구 월드컵북로 45', 'VIP', '2022-11-03 14:20:00'),
('박도윤', 'doyoon.park@example.com', '010-3333-4444', '경기도 성남시 분당구 판교로 200', 'SILVER', '2023-06-21 11:10:00'),
('최지우', 'jiwoo.choi@example.com', '010-4444-5555', '인천시 연수구 송도과학로 12', 'BRONZE', '2024-02-09 16:45:00'),
('정하은', 'haeun.jung@example.com', '010-5555-6666', '부산시 해운대구 센텀중앙로 78', 'GOLD', '2023-03-30 10:00:00'),
('강서준', 'seojun.kang@example.com', '010-6666-7777', '대구시 수성구 동대구로 55', 'SILVER', '2023-09-12 13:30:00'),
('윤지호', 'jiho.yoon@example.com', '010-7777-8888', '광주시 서구 상무중앙로 33', 'BRONZE', '2024-04-18 09:50:00'),
('임채원', 'chaewon.lim@example.com', '010-8888-9999', '대전시 유성구 도안대로 89', 'VIP', '2022-08-07 15:15:00'),
('한지훈', 'jihoon.han@example.com', '010-9999-0000', '울산시 남구 삼산로 21', 'SILVER', '2023-12-01 12:00:00'),
('오예은', 'yeeun.oh@example.com', '010-1212-3434', '서울시 송파구 올림픽로 300', 'GOLD', '2023-05-25 17:40:00'),
('신도현', 'dohyun.shin@example.com', '010-2323-4545', '서울시 강서구 마곡중앙로 15', 'BRONZE', '2024-01-11 10:20:00'),
('배수아', 'sua.bae@example.com', '010-3434-5656', '경기도 수원시 영통구 광교로 60', 'SILVER', '2023-07-19 14:00:00'),
('조은우', 'eunwoo.jo@example.com', '010-4545-6767', '경기도 고양시 일산동구 정발산로 5', 'VIP', '2022-10-22 09:30:00'),
('권유나', 'yuna.kwon@example.com', '010-5656-7878', '서울시 종로구 사직로 8', 'BRONZE', '2024-05-03 11:25:00'),
('장시우', 'siwoo.jang@example.com', '010-6767-8989', '서울시 영등포구 여의대로 24', 'GOLD', '2023-02-14 16:10:00');

INSERT INTO orders (customer_id, product_name, amount, status, ordered_at) VALUES
(1, '무선 이어폰 Pro', 189000, 'DELIVERED', '2024-11-02 10:15:00'),
(1, '스마트워치 밴드', 39000, 'PAID', '2025-01-20 09:40:00'),
(2, '접이식 노트북 거치대', 45000, 'DELIVERED', '2024-09-14 13:20:00'),
(2, '기계식 키보드', 129000, 'DELIVERED', '2024-12-05 11:05:00'),
(2, '4K 웹캠', 79000, 'SHIPPED', '2025-03-01 15:30:00'),
(3, '보조배터리 20000mAh', 32000, 'DELIVERED', '2024-08-22 08:50:00'),
(4, '블루투스 스피커', 65000, 'CANCELLED', '2024-10-10 12:00:00'),
(5, '게이밍 마우스', 55000, 'DELIVERED', '2024-07-30 17:10:00'),
(5, '모니터 받침대', 28000, 'PAID', '2025-02-11 10:45:00'),
(6, 'USB-C 허브', 42000, 'DELIVERED', '2024-11-25 14:15:00'),
(7, '무선 충전기', 25000, 'PENDING', '2025-06-01 09:00:00'),
(8, '노이즈캔슬링 헤드폰', 259000, 'DELIVERED', '2024-06-18 16:20:00'),
(8, '태블릿 케이스', 38000, 'DELIVERED', '2024-12-30 13:40:00'),
(9, '휴대용 SSD 1TB', 149000, 'SHIPPED', '2025-04-05 11:30:00'),
(10, '스마트 플러그 2구', 22000, 'DELIVERED', '2024-09-09 10:00:00'),
(11, '웹캠 조명', 31000, 'PAID', '2025-05-14 15:50:00'),
(12, '컴퓨터 스피커', 47000, 'DELIVERED', '2024-08-08 09:20:00'),
(13, '외장 그래픽 독', 389000, 'DELIVERED', '2024-05-27 12:10:00'),
(14, '유선 이어폰', 15000, 'CANCELLED', '2025-01-03 17:00:00'),
(15, '무선 마우스', 29000, 'DELIVERED', '2024-10-19 14:45:00');

INSERT INTO inquiries (customer_id, category, title, content, status, created_at) VALUES
(1, '배송', '배송이 너무 늦어요', '주문한 지 일주일이 지났는데 아직도 배송 준비 중입니다. 언제 도착하나요?', 'RESOLVED', '2024-11-05 09:30:00'),
(2, '환불', '제품 불량으로 환불 요청', '기계식 키보드 일부 키가 눌리지 않습니다. 환불 부탁드립니다.', 'IN_PROGRESS', '2024-12-08 10:15:00'),
(3, '상품문의', '보조배터리 용량 관련 문의', '20000mAh 제품 실제 충전 가능 횟수가 궁금합니다.', 'CLOSED', '2024-08-23 11:00:00'),
(4, '환불', '주문 취소하고 싶어요', '블루투스 스피커 주문을 취소하고 싶습니다. 아직 배송 전인가요?', 'RESOLVED', '2024-10-10 12:30:00'),
(5, '계정', '비밀번호를 잊어버렸어요', '로그인이 안 됩니다. 비밀번호 재설정 방법을 알려주세요.', 'CLOSED', '2024-07-15 14:20:00'),
(6, '배송', '배송지 변경 요청', '주문 후 배송지를 잘못 입력했습니다. 변경 가능할까요?', 'OPEN', '2025-06-02 09:10:00'),
(7, '기타', '포인트 적립 문의', '이번 구매에 대한 포인트가 적립되지 않았습니다.', 'OPEN', '2025-06-01 10:00:00'),
(8, '상품문의', '헤드폰 노이즈캔슬링 강도 조절', '노이즈캔슬링 강도를 단계별로 조절할 수 있나요?', 'RESOLVED', '2024-06-20 15:40:00'),
(9, '배송', '배송 조회가 안 돼요', '운송장 번호로 조회했는데 정보가 없다고 나옵니다.', 'IN_PROGRESS', '2025-04-06 13:15:00'),
(10, '기타', '영수증 재발급 요청', '지난 주문 건 현금영수증을 재발급받고 싶습니다.', 'CLOSED', '2024-09-11 09:50:00'),
(11, '상품문의', '웹캠 조명 색온도 조절 가능 여부', '조명의 색온도를 조절할 수 있는 기능이 있나요?', 'OPEN', '2025-05-15 16:00:00'),
(13, '환불', '그래픽 독 반품 절차 문의', '단순 변심으로 반품하고 싶습니다. 절차를 알려주세요.', 'IN_PROGRESS', '2024-05-29 10:30:00'),
(14, '환불', '취소된 주문 환불 확인', '주문 취소했는데 환불이 언제 처리되나요?', 'RESOLVED', '2025-01-04 11:20:00'),
(2, '계정', '회원 등급 관련 문의', 'VIP 등급 혜택이 무엇인지 궁금합니다.', 'CLOSED', '2025-01-10 14:00:00'),
(8, '기타', '이벤트 쿠폰 사용 문의', '보유 중인 쿠폰을 이번 주문에 어떻게 적용하나요?', 'OPEN', '2025-06-03 09:45:00');
