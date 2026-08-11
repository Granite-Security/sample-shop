# Product photographs — local copies

Copied from garage (`media.granite-security.org`) on 2026-08-11. These are the
originals behind `product.media`; garage serves them from `garage-pvc`, a
`local-path` volume whose reclaim policy is `Delete`, so deleting the granite
namespace destroys them. This folder is the backup.

`019-seed-si-chocolate.sql` restores the product rows and their media URLs, but
not the objects. After a rebuild, re-upload these under the **same keys** or the
restored rows will point at URLs that 404.

| Product | File | Garage key | Default |
|---|---|---|---|
| 15 Sour Cherry Cinnamon Delice | `15-sour-cherry-cinnamon-delice.jpg` | `products/b8f00853-db0d-4d80-9ee5-dee1fc4cd869/Screenshot_20260810_184357_Google.jpg` | yes |
| 15 Sour Cherry Cinnamon Delice | `15-sour-cherry-cinnamon-delice-2.png` | `products/bb0ef783-8654-4ef1-8ac3-4c1bc545c514/granite.png` |  |
| 16 Pine Nut Truffle | `16-pine-nut-truffle.jpg` | `products/0c90a364-4d84-47cc-811f-8e01d756f9ac/20241130_124022.jpg` |  |
| 17 Wilda Blueberry & Dark Chocolate Bonbon | `17-wilda-blueberry-dark-chocolate-bonbon.jpg` | `products/27118bda-160d-4188-968c-560bc7ed7658/Screenshot_20260810_190335_Gallery.jpg` |  |
| 18 Orange & Orange Chocolate Marmalade | `18-orange-orange-chocolate-marmalade.jpg` | `products/f549d711-f753-4c49-b378-467d60bc2bc6/Screenshot_20260810_190354_Gallery.jpg` |  |
| 19 Salted Caramel & Hazelnut Rocher | `19-salted-caramel-hazelnut-rocher.jpg` | `products/9cc2b0c7-04f6-49ba-ae7b-734244e024b3/1000056027.jpg` |  |
| 20 ROSE & RASPBERRY TRUFFLE | `20-rose-raspberry-truffle.jpg` | `products/8b66e9f7-a59f-40e3-bf3e-42e3b2bc372d/20241130_123956.jpg` |  |
| 21 Callebaut Coconut Velvet Truffle | `21-callebaut-coconut-velvet-truffle.jpg` | `products/92f94685-88f7-425b-9414-179c4546c7f7/20241130_124039.jpg` |  |
| 22 Espresso Ganache Collection | `22-espresso-ganache-collection.jpg` | `products/9d0a82c5-7738-49f9-a066-fa906ea553b7/20241130_124010.jpg` |  |
| 23 Dubai Style Pistachio & Kataifi Truffle | `23-dubai-style-pistachio-kataifi-truffle.jpg` | `products/b8daff38-d7f4-46e1-ac6f-3416d87880c1/20241130_124043.jpg` |  |

Note: `15-sour-cherry-cinnamon-delice-2.png` is the granite logo, attached to that
product as a second media item — not a photograph of chocolate.

