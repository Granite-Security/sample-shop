import { useEffect } from 'react';
import { useLocation } from 'react-router';
import { Hero } from '../components/Hero';
import { Collections } from '../components/Collections';
import { Bestsellers } from '../components/Bestsellers';
import { Craft } from '../components/Craft';
import { Origins } from '../components/Origins';
import { Signature } from '../components/Signature';
import { Testimonials } from '../components/Testimonials';
import { Gifting } from '../components/Gifting';
import { SocialGallery } from '../components/SocialGallery';
import { Newsletter } from '../components/Newsletter';

export function Home() {
  const { hash } = useLocation();

  // Support /#section links arriving from other routes.
  useEffect(() => {
    if (!hash) {
      window.scrollTo(0, 0);
      return;
    }
    // wait a frame so the sections exist before scrolling
    requestAnimationFrame(() => {
      document.querySelector(hash)?.scrollIntoView({ behavior: 'smooth' });
    });
  }, [hash]);

  return (
    <>
      <Hero />
      <Collections />
      <Bestsellers />
      <Craft />
      <Origins />
      <Signature />
      <Testimonials />
      <Gifting />
      <SocialGallery />
      <Newsletter />
    </>
  );
}
