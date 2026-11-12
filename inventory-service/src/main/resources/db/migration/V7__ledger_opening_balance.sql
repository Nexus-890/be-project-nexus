-- TỒN KHO NHẬP TRƯỚC KHI CÓ SỔ KHO (#130) CHƯA CÓ DÒNG NÀO TRONG LEDGER
-- -> GHI 1 DÒNG INTAKE ĐẦU KỲ BẰNG PHẦN CHƯA GHI, ĐỂ TỔNG INTAKE = total_quantity NGAY SAU NÂNG CẤP
-- CHẠY Ở FILE RIÊNG VÌ V6 VỪA THÊM GIÁ TRỊ INTAKE, CÙNG TRANSACTION THÌ POSTGRES KHÔNG CHO DÙNG
INSERT INTO inventory_movements
    (sku_id, warehouse_id, movement_type, quantity, reference_id, created_by, updated_by)
SELECT b.sku_id, b.warehouse_id, 'INTAKE', b.total_quantity - COALESCE(m.recorded, 0), b.id,
       b.updated_by, b.updated_by
FROM inventory_balances b
LEFT JOIN (SELECT sku_id, warehouse_id, SUM(quantity) AS recorded
           FROM inventory_movements
           WHERE movement_type = 'INTAKE'
           GROUP BY sku_id, warehouse_id) m
       ON m.sku_id = b.sku_id AND m.warehouse_id = b.warehouse_id
WHERE b.total_quantity > COALESCE(m.recorded, 0);
