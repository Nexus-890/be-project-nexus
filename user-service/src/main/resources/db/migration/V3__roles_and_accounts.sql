INSERT INTO permissions (code, name) VALUES
                                         ('AUTH.LOGIN', 'Login to the system'),
                                         ('AUTH.LOGOUT', 'Logout from the system'),
                                         ('PROFILE.VIEW', 'View own profile'),
                                         ('PROFILE.UPDATE', 'Update own profile'),
                                         ('PROFILE.CHANGE_PASSWORD', 'Change own password'),
                                         ('PROFILE.RESET_PASSWORD', 'Reset forgotten password'),
                                         ('USER.CREATE', 'Create user account'),
                                         ('USER.UPDATE', 'Update user information'),
                                         ('USER.DELETE', 'Delete user account'),
                                         ('USER.VIEW', 'View user details'),
                                         ('USER.LIST', 'View user list'),
                                         ('USER.CHANGE_PASSWORD', 'Change user password'),
                                         ('USER.REPUTATION.VIEW', 'View user reputation'),
                                         ('USER.REPUTATION.ADJUST', 'Adjust user reputation'),
                                         ('ROLE.CREATE', 'Create role'),
                                         ('ROLE.UPDATE', 'Update role'),
                                         ('ROLE.DELETE', 'Delete role'),
                                         ('ROLE.VIEW', 'View role details'),
                                         ('ROLE.LIST', 'View role list'),
                                         ('PRODUCT.CREATE', 'Create product'),
                                         ('PRODUCT.UPDATE', 'Update product'),
                                         ('PRODUCT.DELETE', 'Delete product'),
                                         ('PRODUCT.VIEW', 'View product details'),
                                         ('PRODUCT.LIST', 'View product list'),
                                         ('PRODUCT.SEARCH', 'Search products'),
                                         ('CATEGORY.CREATE', 'Create category'),
                                         ('CATEGORY.UPDATE', 'Update category'),
                                         ('CATEGORY.DELETE', 'Delete category'),
                                         ('CATEGORY.VIEW', 'View category details'),
                                         ('CATEGORY.LIST', 'View category list'),
                                         ('CART.CREATE', 'Create shopping cart'),
                                         ('CART.UPDATE', 'Update cart items'),
                                         ('CART.REMOVE_ITEM', 'Remove cart item'),
                                         ('CART.VIEW', 'View shopping cart'),
                                         ('CHECKOUT.START', 'Start checkout process'),
                                         ('ORDER.CREATE', 'Create order'),
                                         ('ORDER.CANCEL', 'Cancel order'),
                                         ('ORDER.VIEW', 'View order details'),
                                         ('ORDER.LIST', 'View order list/history'),
                                         ('ORDER.REFUND', 'Request or process refund'),
                                         ('AUCTION.CREATE', 'Create auction'),
                                         ('AUCTION.UPDATE', 'Update auction configuration'),
                                         ('AUCTION.CANCEL', 'Cancel auction'),
                                         ('AUCTION.ADMIN_CANCEL', 'Admin force cancel auction'),
                                         ('AUCTION.VIEW', 'View auction details'),
                                         ('AUCTION.LIST', 'View auction list'),
                                         ('AUCTION.BID', 'Place bid'),
                                         ('AUCTION.VIEW_BID_HISTORY', 'View bid history'),
                                         ('SHIPPING.QUOTE', 'Get shipping fee quote'),
                                         ('SHIPPING.CREATE_SHIPMENT', 'Create shipment'),
                                         ('SHIPPING.VIEW_SHIPMENT', 'View shipment details'),
                                         ('SHIPPING.UPDATE_STATUS', 'Update shipment status'),
                                         ('SHIPPING.MANUAL_OVERRIDE', 'Manual shipment override'),
                                         ('REPUTATION.VIEW', 'View reputation summary'),
                                         ('REPUTATION.RATE', 'Rate transaction partner'),
                                         ('REPUTATION.PENALTY.VIEW', 'View penalty history'),
                                         ('REPUTATION.PENALTY.APPLY', 'Apply reputation penalty'),
                                         ('NOTIFICATION.VIEW', 'View notification history'),
                                         ('NOTIFICATION.MANAGE_PREFERENCE', 'Manage notification preferences'),
                                         ('EVENT_LOG.VIEW', 'View event log list'),
                                         ('EVENT_LOG.VIEW_DETAIL', 'View event log details'),
                                         ('AUDIT_LOG.VIEW', 'View audit logs'),
                                         ('SYSTEM.CONFIG.VIEW', 'View system configuration'),
                                         ('SYSTEM.CONFIG.UPDATE', 'Update system configuration'),
                                         ('SYSTEM.HEALTH.VIEW', 'View system health status')
    ON CONFLICT DO NOTHING;
-- ==========
INSERT INTO roles (code, name, description, is_system) VALUES
                                                           ('ADMIN', 'ADMIN', 'Administrator', true),
                                                           ('SELLER', 'SELLER', 'Seller', true),
                                                           ('BUYER', 'BUYER', 'Buyer', true),
                                                           ('SUPPORT_STAFF', 'SUPPORT_STAFF', 'Support Staff', true)
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         JOIN permissions p ON p.code IN (
                                          'AUTH.LOGIN', 'AUTH.LOGOUT', 'PROFILE.VIEW', 'PROFILE.UPDATE', 'PROFILE.CHANGE_PASSWORD',
                                          'PROFILE.RESET_PASSWORD', 'USER.CREATE', 'USER.UPDATE', 'USER.DELETE', 'USER.VIEW',
                                          'USER.LIST', 'USER.CHANGE_PASSWORD', 'USER.REPUTATION.VIEW', 'USER.REPUTATION.ADJUST',
                                          'ROLE.CREATE', 'ROLE.UPDATE', 'ROLE.DELETE', 'ROLE.VIEW', 'ROLE.LIST', 'PRODUCT.CREATE',
                                          'PRODUCT.UPDATE', 'PRODUCT.DELETE', 'PRODUCT.VIEW', 'PRODUCT.LIST', 'PRODUCT.SEARCH',
                                          'CATEGORY.CREATE', 'CATEGORY.UPDATE', 'CATEGORY.DELETE', 'CATEGORY.VIEW', 'CATEGORY.LIST',
                                          'ORDER.CANCEL', 'ORDER.VIEW', 'ORDER.LIST', 'ORDER.REFUND', 'AUCTION.CREATE',
                                          'AUCTION.UPDATE', 'AUCTION.CANCEL', 'AUCTION.ADMIN_CANCEL', 'AUCTION.VIEW', 'AUCTION.LIST',
                                          'AUCTION.VIEW_BID_HISTORY', 'SHIPPING.QUOTE', 'SHIPPING.CREATE_SHIPMENT',
                                          'SHIPPING.VIEW_SHIPMENT', 'SHIPPING.UPDATE_STATUS', 'SHIPPING.MANUAL_OVERRIDE',
                                          'REPUTATION.VIEW', 'REPUTATION.PENALTY.VIEW', 'REPUTATION.PENALTY.APPLY',
                                          'NOTIFICATION.VIEW', 'EVENT_LOG.VIEW', 'EVENT_LOG.VIEW_DETAIL', 'AUDIT_LOG.VIEW',
                                          'SYSTEM.CONFIG.VIEW', 'SYSTEM.CONFIG.UPDATE', 'SYSTEM.HEALTH.VIEW'
    )
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         JOIN permissions p ON p.code IN (
                                          'AUTH.LOGIN', 'AUTH.LOGOUT', 'PROFILE.VIEW', 'PROFILE.UPDATE', 'PROFILE.CHANGE_PASSWORD',
                                          'PROFILE.RESET_PASSWORD', 'PRODUCT.CREATE', 'PRODUCT.UPDATE', 'PRODUCT.DELETE',
                                          'PRODUCT.VIEW', 'PRODUCT.LIST', 'PRODUCT.SEARCH', 'CATEGORY.VIEW', 'CATEGORY.LIST',
                                          'ORDER.VIEW', 'ORDER.LIST', 'AUCTION.CREATE', 'AUCTION.UPDATE', 'AUCTION.CANCEL',
                                          'AUCTION.VIEW', 'AUCTION.LIST', 'AUCTION.VIEW_BID_HISTORY', 'SHIPPING.QUOTE',
                                          'SHIPPING.VIEW_SHIPMENT', 'REPUTATION.VIEW', 'REPUTATION.RATE', 'NOTIFICATION.VIEW',
                                          'NOTIFICATION.MANAGE_PREFERENCE'
    )
WHERE r.code = 'SELLER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         JOIN permissions p ON p.code IN (
                                          'AUTH.LOGIN', 'AUTH.LOGOUT', 'PROFILE.VIEW', 'PROFILE.UPDATE', 'PROFILE.CHANGE_PASSWORD',
                                          'PROFILE.RESET_PASSWORD', 'PRODUCT.VIEW', 'PRODUCT.LIST', 'PRODUCT.SEARCH', 'CATEGORY.VIEW',
                                          'CATEGORY.LIST', 'CART.CREATE', 'CART.UPDATE', 'CART.REMOVE_ITEM', 'CART.VIEW',
                                          'CHECKOUT.START', 'ORDER.CREATE', 'ORDER.CANCEL', 'ORDER.VIEW', 'ORDER.LIST',
                                          'AUCTION.VIEW', 'AUCTION.LIST', 'AUCTION.BID', 'AUCTION.VIEW_BID_HISTORY', 'SHIPPING.QUOTE',
                                          'SHIPPING.VIEW_SHIPMENT', 'REPUTATION.VIEW', 'REPUTATION.RATE', 'NOTIFICATION.VIEW',
                                          'NOTIFICATION.MANAGE_PREFERENCE'
    )
WHERE r.code = 'BUYER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         JOIN permissions p ON p.code IN (
                                          'AUTH.LOGIN', 'AUTH.LOGOUT', 'PROFILE.VIEW', 'PROFILE.UPDATE', 'PROFILE.CHANGE_PASSWORD',
                                          'PROFILE.RESET_PASSWORD', 'USER.UPDATE', 'USER.VIEW', 'USER.LIST', 'USER.REPUTATION.VIEW',
                                          'ROLE.VIEW', 'ROLE.LIST', 'PRODUCT.VIEW', 'PRODUCT.LIST', 'PRODUCT.SEARCH', 'CATEGORY.VIEW',
                                          'CATEGORY.LIST', 'ORDER.CANCEL', 'ORDER.VIEW', 'ORDER.LIST', 'ORDER.REFUND', 'AUCTION.VIEW',
                                          'AUCTION.LIST', 'AUCTION.VIEW_BID_HISTORY', 'SHIPPING.QUOTE', 'SHIPPING.CREATE_SHIPMENT',
                                          'SHIPPING.VIEW_SHIPMENT', 'SHIPPING.UPDATE_STATUS', 'SHIPPING.MANUAL_OVERRIDE',
                                          'REPUTATION.VIEW', 'REPUTATION.PENALTY.VIEW', 'NOTIFICATION.VIEW', 'EVENT_LOG.VIEW',
                                          'EVENT_LOG.VIEW_DETAIL', 'SYSTEM.HEALTH.VIEW'
    )
WHERE r.code = 'SUPPORT_STAFF'
ON CONFLICT DO NOTHING;

