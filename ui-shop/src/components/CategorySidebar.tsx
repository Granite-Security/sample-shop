import type { Category } from '../types';

interface Props {
  categories: Category[];
  selected: number | null;
  onSelect: (id: number | null) => void;
}

export default function CategorySidebar({ categories, selected, onSelect }: Props) {
  return (
    <aside className="category-sidebar">
      <h3>Categories</h3>
      <ul>
        <li>
          <button
            className={selected === null ? 'active' : ''}
            onClick={() => onSelect(null)}
          >
            All
          </button>
        </li>
        {categories.map(c => (
          <li key={c.id}>
            <button
              className={selected === c.id ? 'active' : ''}
              onClick={() => onSelect(c.id)}
            >
              {c.name}
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}
