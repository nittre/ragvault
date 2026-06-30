USE shop_db;

-- 카테고리
INSERT INTO categories (id, name, parent_id) VALUES
(1, '의류', NULL),
(2, '전자기기', NULL),
(3, '식품', NULL),
(4, '남성 의류', 1),
(5, '여성 의류', 1),
(6, '스마트폰', 2),
(7, '노트북', 2),
(8, '간식', 3),
(9, '음료', 3);

-- 사용자
INSERT INTO users (id, email, name, phone, address, grade, point_balance) VALUES
(1, 'kim@example.com',  '김민준', '010-1234-5678', '서울시 강남구 테헤란로 123', 'GOLD',   8500),
(2, 'lee@example.com',  '이서연', '010-2345-6789', '경기도 성남시 분당구 판교로 456', 'VIP',  32000),
(3, 'park@example.com', '박지훈', '010-3456-7890', '부산시 해운대구 센텀로 789', 'SILVER', 3200),
(4, 'choi@example.com', '최예은', '010-4567-8901', '대구시 수성구 동대구로 321', 'BRONZE',  500),
(5, 'yoon@example.com', '윤도현', '010-5678-9012', '인천시 연수구 송도대로 654', 'SILVER', 1800);

-- 상품
INSERT INTO products (id, category_id, name, description, price, is_active) VALUES
(1,  4, '클래식 슬림핏 셔츠',      '고급 면 소재의 슬림핏 남성 셔츠',           39000, 1),
(2,  4, '캐주얼 치노 팬츠',        '편안한 착용감의 스트레치 치노 팬츠',         55000, 1),
(3,  5, '플로럴 원피스',           '봄/여름 시즌 플로럴 패턴 원피스',            68000, 1),
(4,  5, '오버사이즈 니트 카디건',  '부드러운 울 혼방 오버사이즈 카디건',         72000, 1),
(5,  6, '스마트폰 A55',            '6.4인치 AMOLED, 256GB, 5000mAh',          899000, 1),
(6,  6, '스마트폰 X12 Pro',        '6.7인치 LTPO, 512GB, 트리플 카메라',      1350000, 1),
(7,  7, '울트라슬림 노트북 14',    'Intel i7, 16GB RAM, 512GB SSD, 1.2kg',   1490000, 1),
(8,  7, '게이밍 노트북 15',        'AMD Ryzen 9, RTX 4070, 32GB RAM',        2390000, 1),
(9,  8, '수제 마들렌 12개입',      '버터 듬뿍 들어간 프리미엄 수제 마들렌',     18000, 1),
(10, 9, '콜드브루 커피 500ml',     '48시간 더치 추출 콜드브루',                  6500, 1);

-- 재고
INSERT INTO inventory (product_id, quantity) VALUES
(1,  150),
(2,   80),
(3,   60),
(4,   45),
(5,  200),
(6,   75),
(7,   30),
(8,   18),
(9,  300),
(10, 500);

-- 주문 1 (배송 완료)
INSERT INTO orders (id, user_id, status, total_amount, discount_amount, point_used, point_earned, recipient_name, recipient_phone, address, ordered_at)
VALUES (1, 1, 'DELIVERED', 94000, 0, 0, 940, '김민준', '010-1234-5678', '서울시 강남구 테헤란로 123', '2026-06-01 10:23:00');

INSERT INTO order_items (order_id, product_id, product_name, unit_price, quantity) VALUES
(1, 1, '클래식 슬림핏 셔츠', 39000, 1),
(1, 2, '캐주얼 치노 팬츠',   55000, 1);

INSERT INTO shipping (order_id, courier, tracking_number, status, shipped_at, delivered_at) VALUES
(1, 'CJ대한통운', '123456789012', 'DELIVERED', '2026-06-02 14:00:00', '2026-06-03 11:30:00');

-- 주문 2 (배송 중)
INSERT INTO orders (id, user_id, status, total_amount, discount_amount, point_used, point_earned, recipient_name, recipient_phone, address, ordered_at)
VALUES (2, 2, 'SHIPPED', 1350000, 0, 5000, 13450, '이서연', '010-2345-6789', '경기도 성남시 분당구 판교로 456', '2026-06-20 15:42:00');

INSERT INTO order_items (order_id, product_id, product_name, unit_price, quantity) VALUES
(2, 6, '스마트폰 X12 Pro', 1350000, 1);

INSERT INTO shipping (order_id, courier, tracking_number, status, shipped_at) VALUES
(2, '로젠택배', '987654321098', 'IN_TRANSIT', '2026-06-21 09:00:00');

-- 주문 3 (결제 완료, 준비 중)
INSERT INTO orders (id, user_id, status, total_amount, discount_amount, point_used, point_earned, recipient_name, recipient_phone, address, ordered_at)
VALUES (3, 3, 'PREPARING', 1490000, 0, 0, 14900, '박지훈', '010-3456-7890', '부산시 해운대구 센텀로 789', '2026-06-24 09:11:00');

INSERT INTO order_items (order_id, product_id, product_name, unit_price, quantity) VALUES
(3, 7, '울트라슬림 노트북 14', 1490000, 1);

INSERT INTO shipping (order_id, status) VALUES
(3, 'READY');

-- 주문 4 (소액 주문, 배송 완료)
INSERT INTO orders (id, user_id, status, total_amount, discount_amount, point_used, point_earned, recipient_name, recipient_phone, address, ordered_at)
VALUES (4, 5, 'DELIVERED', 31000, 0, 0, 310, '윤도현', '010-5678-9012', '인천시 연수구 송도대로 654', '2026-06-15 18:05:00');

INSERT INTO order_items (order_id, product_id, product_name, unit_price, quantity) VALUES
(4, 9,  '수제 마들렌 12개입', 18000, 1),
(4, 10, '콜드브루 커피 500ml', 6500, 2);

INSERT INTO shipping (order_id, courier, tracking_number, status, shipped_at, delivered_at) VALUES
(4, '한진택배', '555444333222', 'DELIVERED', '2026-06-16 10:00:00', '2026-06-17 14:20:00');

-- 적립금 내역
INSERT INTO point_history (user_id, order_id, type, amount, balance, description) VALUES
(1, 1, 'EARN',  940,  940,  '주문 #1 구매 적립'),
(1, NULL, 'ADMIN', 7560, 8500, '웰컴 포인트 지급'),
(2, 2, 'USE',  -5000, 27000, '주문 #2 포인트 사용'),
(2, 2, 'EARN', 13450, 40450, '주문 #2 구매 적립'),
(2, NULL, 'ADMIN', -8450, 32000, '관리자 조정'),
(3, 3, 'EARN', 14900, 14900, '주문 #3 구매 적립'),
(3, NULL, 'EXPIRE', -11700, 3200, '포인트 만료'),
(5, 4, 'EARN', 310, 310, '주문 #4 구매 적립'),
(5, NULL, 'ADMIN', 1490, 1800, '이벤트 포인트');
