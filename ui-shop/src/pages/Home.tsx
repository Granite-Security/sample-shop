import { Link } from 'react-router';
import { useEffect, useState } from 'react';
import { api } from '../api';

export default function Home() {
  const [greeting, setGreeting] = useState('');

  useEffect(() => {
    api.shop.greetings().then(d => setGreeting(d.message)).catch(() => {});
  }, []);

  return (
    <div className="page home">
      <h1>Welcome to the Shop</h1>
      {greeting && <p className="greeting">{greeting}</p>}
      <div className="home-links">
        <Link to="/catalog" className="btn">Browse Products</Link>
        <Link to="/orders" className="btn">My Orders</Link>
      </div>
    </div>
  );
}
