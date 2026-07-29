import { Hero } from '../components/Hero';
import { Featured } from '../components/Featured';
import { StoryTeaser } from '../components/StoryTeaser';
import { Signature } from '../components/Signature';
import { Testimonials } from '../components/Testimonials';

/**
 * Deliberately short. The catalog lives on /shop, the long-form craft and
 * origins story on /our-story, gifting on /gifts — the home page only has to
 * invite, not to say everything.
 */
export function Home() {
  return (
    <>
      <Hero />
      <Featured />
      <StoryTeaser />
      <Signature />
      <Testimonials />
    </>
  );
}
