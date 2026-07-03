import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api';
import type { Product, Category } from '../types';
import ProductCard from '../components/ProductCard';
import CategorySidebar from '../components/CategorySidebar';

export default function Catalog() {
  const [products, setProducts] = useState<Product[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [searchParams, setSearchParams] = useSearchParams();

  const selectedCategory = searchParams.get('category')
    ? Number(searchParams.get('category'))
    : null;

  useEffect(() => {
    Promise.all([
      api.getProducts().then(r => setProducts(r.items)),
      api.getCategories().then(r => setCategories(r.items)),
    ]).catch(e => setError(e instanceof Error ? e.message : 'Failed to load catalog'))
      .finally(() => setLoading(false));
  }, []);

  const filtered = selectedCategory
    ? products.filter(p => p.categoryId === selectedCategory)
    : products;

  const handleCategorySelect = (id: number | null) => {
    if (id) {
      setSearchParams({ category: String(id) });
    } else {
      setSearchParams({});
    }
  };

  if (loading) return <div className="page"><p>Loading...</p></div>;

  if (error) return (
    <div className="page catalog-page">
      <p className="error">{error}</p>
    </div>
  );

  return (
    <div className="page catalog-page">
      <CategorySidebar
        categories={categories}
        selected={selectedCategory}
        onSelect={handleCategorySelect}
      />
      <div className="product-grid">
        {filtered.map(p => <ProductCard key={p.id} product={p} />)}
        {filtered.length === 0 && <p>No products found.</p>}
      </div>
    </div>
  );
}
